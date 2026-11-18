package dev.foliopay.service.impl;

import dev.foliopay.domain.exception.IdempotencyKeyReusedException;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.gateway.PspClient.PspResult;
import dev.foliopay.gateway.PspDeclinedException;
import dev.foliopay.gateway.PspRejectedException;
import dev.foliopay.gateway.PspTransientException;
import dev.foliopay.logging.Redacted;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.service.BusinessDateService;
import dev.foliopay.service.CardVault;
import dev.foliopay.service.ChargeService;
import dev.foliopay.service.support.IdempotentStore;
import io.github.resilience4j.retry.Retry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The safety rests on three things: a unique idempotency key in the database, the same key sent to the processor on every retry, and the
 * card number existing in memory only inside one short call (never while a retry waits).
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
      ChargeRepository repository, CardVault vault, PspClient psp, IdempotentStore store, @Qualifier("pspRetry") Retry pspRetry, BusinessDateService businessDates) {
    this.repository = repository;
    this.vault = vault;
    this.psp = psp;
    this.store = store;
    this.pspRetry = pspRetry;
    this.businessDates = businessDates;
  }

  @Override
  public Charge charge(ChargeCommand command) {
    Money amount = command.amount().requirePositive();
    if (!vault.exists(command.cardToken())) {
      throw new InvalidRequestException("Unknown card token");
    }
    Charge charge = repository.findByIdempotencyKey(command.idempotencyKey()).orElseGet(() -> create(command, amount));
    if (!charge.matches(command.bookingRef(), command.folioId(), command.businessDate(), command.cardToken(), amount)) {
      throw new IdempotencyKeyReusedException();
    }
    return charge.getStatus() == Charge.Status.PENDING ? execute(charge) : charge;
  }

  /**
   * Only a new charge asks the PMS for the business date (outside any transaction); a repeat keeps the date it was taken on. The caller
   * has just looked the key up, so this inserts first and reads only if a parallel twin won the insert.
   */
  private Charge create(ChargeCommand command, Money amount) {
    LocalDate businessDate = command.businessDate() != null ? command.businessDate() : businessDates.resolve(null);
    return store.insertOrFind(
        () -> repository.findByIdempotencyKey(command.idempotencyKey()),
        () -> repository.saveAndFlush(new Charge(command.idempotencyKey(), command.bookingRef(), command.folioId(), businessDate, command.cardToken(), amount)));
  }

  @Override
  public Optional<Charge> find(String idempotencyKey) {
    return repository.findByIdempotencyKey(idempotencyKey);
  }

  @Override
  public List<Charge> findAll() {
    return repository.findAll(PageRequest.of(0, 500, Sort.by("createdAt").descending())).getContent();
  }

  private Charge execute(Charge charge) {
    String key = charge.getIdempotencyKey();
    Money amount = charge.amount();
    PspResult auth = null;
    try {
      // The card is lent for each attempt on its own, so it is not held in memory while a retry waits.
      auth =
          pspRetry.executeSupplier(
              () -> vault.withCard(charge.getCardToken(), (pan, expiry) -> psp.authorize(key + ":auth", pan, expiry, amount)));
      PspResult authorised = auth;
      PspResult capture = pspRetry.executeSupplier(() -> psp.capture(key + ":cap", authorised.ref(), amount));
      charge.captured(capture.ref());
      log.info("Charge {} captured {} {}", charge.getId(), amount.minor(), amount.currency());
    } catch (PspRejectedException e) {
      charge.declined(e.getMessage()); // final: the same key gets the same refusal, and a payment link is given back
      log.error("Charge {} refused by the processor, check our side: {}", charge.getId(), charge.getLastError());
      releaseHold(key, auth);
    } catch (PspDeclinedException e) {
      charge.declined(e.getMessage());
      log.warn("Charge {} declined: {}", charge.getId(), charge.getLastError());
      releaseHold(key, auth);
    } catch (PspTransientException e) {
      charge.failedAttempt(e.getMessage()); // stays PENDING: the caller retries with the same key
      log.warn("Charge {} still pending after retries: {}", charge.getId(), charge.getLastError());
    } catch (RuntimeException e) {
      // A reply we could not read, or a bug. The capture may have happened, so the hold is NOT released and the charge stays PENDING:
      // a retry with the same key asks the processor again, which answers with the first result. What we know is saved, never lost.
      charge.failedAttempt("Unexpected error: " + e.getClass().getSimpleName());
      log.error("Charge {} left pending after an unexpected error", charge.getId(), Redacted.of(e));
    }
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
  private void releaseHold(String key, PspResult auth) {
    if (auth == null) {
      return;
    }
    try {
      psp.voidAuthorization(key + ":void", auth.ref());
    } catch (RuntimeException e) {
      log.error("Could not release the authorisation hold for charge key {}: {}", key, e.getClass().getSimpleName());
    }
  }
}
