package dev.foliopay.service.impl;

import dev.foliopay.domain.exception.IdempotencyKeyReusedException;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.gateway.PspClient.PspResult;
import dev.foliopay.gateway.PspDeclinedException;
import dev.foliopay.gateway.PspRejectedException;
import dev.foliopay.gateway.PspTransientException;
import dev.foliopay.gateway.PspUnreadableReplyException;
import dev.foliopay.logging.AuditLog;
import dev.foliopay.logging.AuditLog.Event;
import dev.foliopay.logging.Redacted;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.service.BusinessDateService;
import dev.foliopay.service.CardVault;
import dev.foliopay.service.ChargeService;
import dev.foliopay.service.support.IdempotentStore;
import dev.foliopay.service.support.Listing;
import io.github.resilience4j.retry.Retry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The safety rests on three things: a unique idempotency key per client in the database, the same key sent to the processor on every
 * retry, and the card number existing in memory only inside one short call (never while a retry waits).
 */
@Service
class ChargeServiceImpl implements ChargeService {
  private static final Logger log = LoggerFactory.getLogger(ChargeServiceImpl.class);
  private final ChargeRepository repository;
  private final CardVault vault;
  private final PspClient psp;
  private final IdempotentStore store;
  private final Retry pspRetry;
  private final BusinessDateService businessDates;

  ChargeServiceImpl(
      ChargeRepository repository, CardVault vault, PspClient psp, IdempotentStore store, @Qualifier("pspRetry") Retry pspRetry,
      BusinessDateService businessDates) {
    this.repository = repository;
    this.vault = vault;
    this.psp = psp;
    this.store = store;
    this.pspRetry = pspRetry;
    this.businessDates = businessDates;
  }

  @Override
  public Charge charge(ChargeCommand command) {
    log.debug("charge(client={}, key={}, booking={}, folio={}, amount={} {})", command.clientId(), command.idempotencyKey(), command.bookingRef(),
        command.folioId(), command.amount().minor(), command.amount().currency());
    Money amount = command.amount().requirePositive();
    // The key first: a repeat gets its first result whatever has changed since, its card purged included (D-44). Only a new charge needs it.
    Optional<Charge> stored = repository.findByClientIdAndIdempotencyKey(command.clientId(), command.idempotencyKey());
    if (stored.isEmpty() && !vault.exists(command.cardToken())) {
      throw new InvalidRequestException("Unknown card token");
    }
    Charge charge = stored.orElseGet(() -> create(command, amount));
    if (!charge.matches(command.bookingRef(), command.folioId(), command.businessDate(), command.cardToken(), amount)) {
      log.info("Charge {} refused a repeat of key {} with different parameters", charge.getId(), command.idempotencyKey());
      throw new IdempotencyKeyReusedException();
    }
    if (charge.getStatus() != Charge.Status.PENDING) {
      log.debug("Charge {} is already {}: returned as stored", charge.getId(), charge.getStatus());
      return charge;
    }
    return execute(charge);
  }

  /**
   * Only a new charge asks the PMS for the business date (outside any transaction); a repeat keeps the date it was taken on. The caller
   * has just looked the key up, so this inserts first and reads only if a parallel twin won the insert.
   */
  private Charge create(ChargeCommand command, Money amount) {
    LocalDate businessDate = command.businessDate() != null ? command.businessDate() : businessDates.resolve(null);
    Charge created = store.insertOrFind(
        () -> repository.findByClientIdAndIdempotencyKey(command.clientId(), command.idempotencyKey()),
        () -> repository.saveAndFlush(new Charge(
            command.clientId(), command.idempotencyKey(), command.bookingRef(), command.folioId(), businessDate, command.cardToken(), amount)));
    log.debug("Charge {} stored for key {} on business date {}", created.getId(), command.idempotencyKey(), created.getBusinessDate());
    return created;
  }

  @Override
  public Optional<Charge> find(String clientId, String idempotencyKey) {
    log.debug("find(client={}, key={})", clientId, idempotencyKey);
    return repository.findByClientIdAndIdempotencyKey(clientId, idempotencyKey);
  }

  @Override
  public List<Charge> findAll() {
    log.debug("findAll(limit={})", Listing.NEWEST);
    return repository.findAll(Listing.newest("createdAt")).getContent();
  }

  private Charge execute(Charge charge) {
    Money amount = charge.amount();
    PspResult auth = null;
    log.debug("Charge {} attempt {}: authorising {} {}", charge.getId(), charge.getAttempts() + 1, amount.minor(), amount.currency());
    try {
      auth = authorise(charge, amount);
      capture(charge, auth, amount);
    } catch (PspRejectedException e) {
      refused(charge, e.getMessage(), auth);
    } catch (PspDeclinedException e) {
      declined(charge, e.getMessage(), auth);
    } catch (PspTransientException e) {
      charge.recordFailedAttempt(e.getMessage()); // stays PENDING: the caller retries with the same key
      log.warn("Charge {} still pending after retries: {}", charge.getId(), charge.getLastError());
    } catch (NotFoundException e) {
      cardGone(charge);
    } catch (PspUnreadableReplyException e) {
      // The processor said yes but we cannot read what to: the step may have happened, so the charge stays PENDING and the hold stays.
      charge.recordFailedAttempt(e.getMessage());
      log.error("Charge {} left pending: {}", charge.getId(), charge.getLastError());
    } catch (RuntimeException e) {
      // A bug, or a reply we could not read: the capture may have happened, so the hold stays and the charge stays PENDING. A retry with
      // the same key asks the processor again, which answers with the first result.
      charge.recordFailedAttempt("Unexpected error: " + e.getClass().getSimpleName());
      log.error("Charge {} left pending after an unexpected error", charge.getId(), Redacted.of(e));
    }
    return save(charge);
  }

  /** The card is lent for each attempt on its own, so it is not held in memory while a retry waits. */
  private PspResult authorise(Charge charge, Money amount) {
    PspResult auth = pspRetry.executeSupplier(
        () -> vault.withCard(charge.getCardToken(), (pan, expiry) -> psp.authorize(charge.processorKey("auth"), pan, expiry, amount)));
    log.debug("Charge {} authorised as {}: capturing", charge.getId(), auth.ref());
    return auth;
  }

  private void capture(Charge charge, PspResult auth, Money amount) {
    PspResult capture = pspRetry.executeSupplier(() -> psp.capture(charge.processorKey("cap"), auth.ref(), amount));
    charge.markCaptured(capture.ref());
    log.info("Charge {} captured {} {} as {}", charge.getId(), amount.minor(), amount.currency(), capture.ref());
    audit(Event.PAYMENT_CAPTURED, charge).success();
  }

  /** Final: the same key gets the same refusal, and a payment link is given back. Ours to fix, not the guest's card. */
  private void refused(Charge charge, String reason, PspResult auth) {
    charge.markDeclined(reason);
    log.error("Charge {} refused by the processor, check our side: {}", charge.getId(), charge.getLastError());
    audit(Event.PAYMENT_REFUSED, charge).detail("reason", charge.getLastError()).failure();
    releaseHold(charge, auth);
  }

  private void declined(Charge charge, String reason, PspResult auth) {
    charge.markDeclined(reason);
    log.warn("Charge {} declined: {}", charge.getId(), charge.getLastError());
    audit(Event.PAYMENT_DECLINED, charge).detail("reason", charge.getLastError()).failure();
    releaseHold(charge, auth);
  }

  /**
   * The card was purged after the charge was checked and before its first attempt (D-44). Nothing reached the processor, and a card
   * with a payment pending is never purged, so no earlier attempt did either: refusing for good is safe, and gives a link back.
   */
  private void cardGone(Charge charge) {
    charge.markDeclined("The card is no longer stored: its retention period ended");
    log.warn("Charge {} refused: its card was purged before the first attempt", charge.getId());
    audit(Event.PAYMENT_DECLINED, charge).detail("reason", "card-purged").failure();
  }

  private static AuditLog.Entry audit(Event event, Charge charge) {
    return AuditLog.event(event).resource("charge:" + charge.getId()).detail("key", charge.getIdempotencyKey())
        .detail("amount", charge.getAmountMinor()).detail("currency", charge.getCurrency());
  }

  private Charge save(Charge charge) {
    return store.saveMerging(
        charge,
        repository::save,
        () -> repository.findById(charge.getId()).orElseThrow(),
        Charge::isBehind,
        stored -> {
          stored.adopt(charge);
          return stored;
        });
  }

  /** A capture was declined after the card was authorised: release the hold, or a card loaded for one amount stays short. Best effort. */
  private void releaseHold(Charge charge, PspResult auth) {
    if (auth == null) {
      return;
    }
    try {
      psp.voidAuthorization(charge.processorKey("void"), auth.ref());
      log.info("Charge {} released the authorisation hold {}", charge.getId(), auth.ref());
    } catch (RuntimeException e) {
      log.error("Charge {} could not release the authorisation hold {}: {}", charge.getId(), auth.ref(), e.getClass().getSimpleName());
    }
  }
}
