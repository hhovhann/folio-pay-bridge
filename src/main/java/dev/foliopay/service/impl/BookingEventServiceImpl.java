package dev.foliopay.service.impl;

import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.exception.DomainException;
import dev.foliopay.domain.exception.GoneException;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.BookingEvent;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.PaymentLink;
import dev.foliopay.domain.rule.PaymentRequestPolicy;
import dev.foliopay.domain.rule.PaymentRequestPolicy.Decision;
import dev.foliopay.repository.PaymentLinkRepository;
import dev.foliopay.service.BookingEventService;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.PaymentCommand;
import dev.foliopay.service.support.Fingerprint;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
class BookingEventServiceImpl implements BookingEventService {
  private final PaymentLinkRepository links;
  private final PaymentService payments;

  BookingEventServiceImpl(PaymentLinkRepository links, PaymentService payments) {
    this.links = links;
    this.payments = payments;
  }

  @Override
  public Outcome handle(BookingEvent event, LocalDate today) {
    try {
      return apply(event, today);
    } catch (DataIntegrityViolationException twin) {
      return apply(event, today); // a parallel twin created the live link first: re-read and treat this as the repeat it is
    }
  }

  private Outcome apply(BookingEvent event, LocalDate today) {
    List<PaymentLink> existing = links.findByBookingRef(event.bookingRef());
    boolean paid = existing.stream().anyMatch(PaymentLink::isPaid);
    boolean paying = existing.stream().anyMatch(PaymentLink::isPaying);
    PaymentLink open = existing.stream().filter(PaymentLink::isOpen).findFirst().orElse(null);
    if (paying) {
      return new Outcome(existing, false, "A payment is in progress: this change is not applied yet, send it again once the payment settles");
    }
    return event.type() == BookingEvent.Type.CANCELLED ? cancel(event, open, paid) : upsert(event, existing, open, paid, today);
  }

  /**
   * One card holds the claim on a link at a time. A repeat by the same card resumes the same idempotent charge; another card is refused
   * while a payment is in progress or after the link is paid. A decline gives the link back; a capture closes it.
   */
  @Override
  public PaymentService.Result pay(String linkId, String cardToken, LocalDate businessDate) {
    PaymentLink link = links.findById(linkId).orElseThrow(() -> new NotFoundException("Unknown payment link"));
    String fingerprint = Fingerprint.of(cardToken);
    if (link.isOpen()) {
      if (links.claim(linkId, fingerprint) == 0) {
        throw new ConflictException("Another payment on this link is in progress");
      }
    } else if (!((link.isPaying() || link.isPaid()) && link.isHeldBy(fingerprint))) {
      throw link.isPaying() ? new ConflictException("Another payment on this link is in progress") : new GoneException("This payment link is " + link.getStatus());
    }
    PaymentService.Result result = charge(link, fingerprint, cardToken, businessDate);
    if (result.charge().getStatus() == Charge.Status.CAPTURED) {
      links.complete(linkId, fingerprint); // closed once the money is captured, even if the folio posting is waiting
    } else if (result.charge().getStatus() == Charge.Status.DECLINED) {
      links.release(linkId, fingerprint);
    }
    return result; // PENDING keeps the claim: the same card retries the same charge
  }

  private PaymentService.Result charge(PaymentLink link, String fingerprint, String cardToken, LocalDate businessDate) {
    String key = "link:" + link.getId() + ":" + fingerprint;
    try {
      return payments.pay(new PaymentCommand(key, link.getBookingRef(), link.getFolioId(), cardToken, link.amount(), businessDate));
    } catch (DomainException refusedBeforeAnyMoneyMoved) {
      links.release(link.getId(), fingerprint);
      throw refusedBeforeAnyMoneyMoved;
    }
  }

  @Override
  public List<PaymentLink> findAll() {
    return links.findAll(PageRequest.of(0, 500, Sort.by("id").descending())).getContent();
  }

  private Outcome cancel(BookingEvent event, PaymentLink open, boolean paid) {
    if (open != null) {
      open.cancel();
      links.save(open);
    }
    String note = paid ? "Cancelled after payment: review the refund policy" : "Open request cancelled";
    return new Outcome(links.findByBookingRef(event.bookingRef()), paid, note);
  }

  private Outcome upsert(BookingEvent event, List<PaymentLink> existing, PaymentLink open, boolean paid, LocalDate today) {
    if (paid) {
      return new Outcome(existing, true, "Already paid: a changed total needs a manual balance or refund decision");
    }
    Decision decision = PaymentRequestPolicy.decide(event.ratePlan(), event.arrival(), event.total().minor(), today);
    if (open != null && open.hasTerms(decision.amountMinor(), decision.dueDate())) {
      return new Outcome(existing, false, "No change: the open request already matches the policy");
    }
    if (open != null) {
      open.supersede();
      links.saveAndFlush(open);
    }
    links.saveAndFlush(new PaymentLink(event.bookingRef(), event.folioId(), new Money(decision.amountMinor(), event.total().currency()), decision.dueDate(), decision.reason()));
    return new Outcome(links.findByBookingRef(event.bookingRef()), false, decision.reason());
  }
}
