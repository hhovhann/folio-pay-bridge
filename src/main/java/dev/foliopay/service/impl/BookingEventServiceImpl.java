package dev.foliopay.service.impl;

import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.exception.GoneException;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.BookingEvent;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.PaymentLink;
import dev.foliopay.domain.rule.PaymentRequestPolicy;
import dev.foliopay.domain.rule.PaymentRequestPolicy.Decision;
import dev.foliopay.domain.rule.SafeText;
import dev.foliopay.repository.PaymentLinkRepository;
import dev.foliopay.service.BookingEventService;
import dev.foliopay.service.ChargeService;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.PaymentCommand;
import dev.foliopay.service.support.Fingerprint;
import dev.foliopay.service.support.Listing;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class BookingEventServiceImpl implements BookingEventService {
  private static final String IN_PROGRESS = "A payment is in progress: this change is not applied yet, send it again once the payment settles";
  private static final int MAX_DESCRIPTION = 120;
  /**
   * Remote requests live in their own namespace: a booking reference can never contain a colon, so an app's request can never replace,
   * or be replaced by, a booking's deposit link, and a payments:write client cannot change what only bookings:write may.
   */
  static final String REMOTE_PREFIX = "req:";
  private static final Logger log = LoggerFactory.getLogger(BookingEventServiceImpl.class);
  private final PaymentLinkRepository links;
  private final PaymentService payments;
  private final ChargeService charges;
  private final TransactionTemplate tx;
  private final PaymentRequestPolicy policy;

  BookingEventServiceImpl(
      PaymentLinkRepository links, PaymentService payments, ChargeService charges, TransactionTemplate tx, PaymentRequestPolicy policy) {
    this.links = links;
    this.payments = payments;
    this.charges = charges;
    this.tx = tx;
    this.policy = policy;
  }

  @Override
  public Outcome handle(BookingEvent event, LocalDate today) {
    log.debug("handle(type={}, booking={}, ratePlan={}, arrival={}, total={} {}, today={})", event.type(), event.bookingRef(), event.ratePlan(),
        event.arrival(), event.total().minor(), event.total().currency(), today);
    return onceOrAsTheRepeat(() -> apply(event, today));
  }

  @Override
  public Outcome request(PaymentRequest request, LocalDate today) {
    log.debug("request(reference={}, folio={}, amount={} {}, due={}, today={})", request.reference(), request.folioId(), request.amount().minor(),
        request.amount().currency(), request.dueDate(), today);
    if (request.dueDate().isBefore(today)) {
      throw new InvalidRequestException("The due date is in the past");
    }
    Money amount = request.amount().requirePositive();
    String reference = REMOTE_PREFIX + request.reference();
    log.debug("Payment request {} for folio {}: {} {} due {}", reference, request.folioId(), amount.minor(), amount.currency(), request.dueDate());
    return onceOrAsTheRepeat(() -> {
      Links state = Links.of(links.findByBookingRef(reference));
      if (state.paying()) {
        return new Outcome(state.all(), false, IN_PROGRESS);
      }
      if (state.paid()) {
        return new Outcome(state.all(), false, "Already paid: a new amount needs a separate request");
      }
      String reason = request.description() == null || request.description().isBlank() ? "Payment request" : SafeText.clean(request.description(), MAX_DESCRIPTION);
      return offer(reference, request.folioId(), amount, request.dueDate(), reason, state.open());
    });
  }

  /**
   * A parallel twin got there first: it created the live link (we lose the unique slot) or superseded the open one (we lose the version
   * check). Either way, re-read and treat this as the repeat it is, so the same request twice is "no change", never a 409.
   */
  private static Outcome onceOrAsTheRepeat(Supplier<Outcome> work) {
    try {
      return work.get();
    } catch (DataIntegrityViolationException | OptimisticLockingFailureException twin) {
      return work.get();
    }
  }

  private record Links(List<PaymentLink> all, boolean paid, boolean paying, PaymentLink open) {
    static Links of(List<PaymentLink> all) {
      return new Links(all, all.stream().anyMatch(PaymentLink::isPaid), all.stream().anyMatch(PaymentLink::isPaying),
          all.stream().filter(PaymentLink::isOpen).findFirst().orElse(null));
    }
  }

  private Outcome apply(BookingEvent event, LocalDate today) {
    Links state = Links.of(links.findByBookingRef(event.bookingRef()));
    if (state.paying()) {
      return new Outcome(state.all(), false, IN_PROGRESS);
    }
    return event.type() == BookingEvent.Type.CANCELLED ? cancel(event, state.open(), state.paid()) : upsert(event, state, today);
  }

  /**
   * One card holds the claim on a link at a time. A repeat by the same card resumes the same idempotent charge; another card is refused
   * while a payment is in progress or after the link is paid. A decline gives the link back; a capture closes it; a pending charge keeps
   * it, also when the request failed after the charge was created.
   */
  @Override
  public PaymentService.Result pay(String linkId, String cardToken, LocalDate businessDate) {
    log.debug("pay(link={}, card={}, businessDate={})", linkId, Fingerprint.card(cardToken), businessDate);
    PaymentLink link = links.findById(linkId).orElseThrow(() -> new NotFoundException("Unknown payment link"));
    String fingerprint = Fingerprint.of(cardToken);
    log.debug("Link {} ({}) paid by card {}", linkId, link.getStatus(), fingerprint);
    if (link.isOpen()) {
      if (links.claim(linkId, fingerprint) == 0) {
        throw new ConflictException("Another payment on this link is in progress");
      }
    } else if (!((link.isPaying() || link.isPaid()) && link.isHeldBy(fingerprint))) {
      throw link.isPaying() ? new ConflictException("Another payment on this link is in progress") : new GoneException("This payment link is " + link.getStatus());
    }
    String key = "link:" + linkId + ":" + fingerprint;
    PaymentService.Result result;
    try {
      result = payments.pay(PaymentCommand.bridge(key, link.getBookingRef(), link.getFolioId(), cardToken, link.amount(), businessDate));
    } catch (RuntimeException failure) {
      // Whether money may have moved is a fact in the database, not something the type of the exception can tell.
      settleClaim(linkId, fingerprint, storedStatus(key));
      throw failure;
    }
    settleClaim(linkId, fingerprint, result.charge().getStatus());
    return result;
  }

  /**
   * A capture closes the link, even if the folio posting is still waiting. A decline, or no charge at all, gives the link back for another
   * card. A pending charge keeps the claim: the money may have moved, so only the same card may retry the same charge.
   */
  private void settleClaim(String linkId, String fingerprint, Charge.Status status) {
    if (status == Charge.Status.CAPTURED) {
      if (links.complete(linkId, fingerprint) == 0) {
        // The money is taken; only the link's state is behind. Never fail the guest's payment for it, but make it visible.
        log.error("Payment link {} was captured but is no longer held by this card: check the link and the booking", linkId);
      }
    } else if (status == null || status == Charge.Status.DECLINED) {
      links.release(linkId, fingerprint);
    }
  }

  /** Null when no charge was created. If even that cannot be read, PENDING: keeping the claim is the answer that can never charge twice. */
  private Charge.Status storedStatus(String key) {
    try {
      return charges.find(Charge.BRIDGE_CLIENT, key).map(Charge::getStatus).orElse(null);
    } catch (RuntimeException unreadable) {
      return Charge.Status.PENDING;
    }
  }

  @Override
  public List<PaymentLink> findAll() {
    log.debug("findAll(limit={})", Listing.NEWEST);
    return links.findAll(Listing.newest("id")).getContent();
  }

  private Outcome cancel(BookingEvent event, PaymentLink open, boolean paid) {
    if (open != null) {
      open.cancel();
      links.save(open);
    }
    String note = paid ? "Cancelled after payment: review the refund policy" : "Open request cancelled";
    return new Outcome(links.findByBookingRef(event.bookingRef()), paid, note);
  }

  private Outcome upsert(BookingEvent event, Links state, LocalDate today) {
    if (state.paid()) {
      return new Outcome(state.all(), true, "Already paid: a changed total needs a manual balance or refund decision");
    }
    Decision decision = policy.decide(event.ratePlan(), event.arrival(), event.total().minor(), today);
    return offer(event.bookingRef(), event.folioId(), new Money(decision.amountMinor(), event.total().currency()), decision.dueDate(), decision.reason(), state.open());
  }

  /** Keeps an open request with the same terms (a repeat changes nothing); otherwise replaces it with one on the new terms. */
  private Outcome offer(String reference, String folioId, Money amount, LocalDate dueDate, String reason, PaymentLink open) {
    if (open != null && open.hasTerms(folioId, amount, dueDate)) {
      return new Outcome(links.findByBookingRef(reference), false, "No change: the open request already has these terms");
    }
    // One transaction: the old link is superseded only if the new one is stored, so a booking is never left with no live link.
    tx.executeWithoutResult(status -> {
      if (open != null) {
        open.supersede();
        links.saveAndFlush(open);
      }
      links.saveAndFlush(new PaymentLink(reference, folioId, amount, dueDate, reason));
    });
    log.info("Payment request for {} is now {} {} due {}{}", reference, amount.minor(), amount.currency(), dueDate, open != null ? " (replaced the open one)" : "");
    return new Outcome(links.findByBookingRef(reference), false, reason);
  }
}
