package dev.foliopay.service.impl;

import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.gateway.PspClient.PspResult;
import dev.foliopay.gateway.PspDeclinedException;
import dev.foliopay.gateway.PspTransientException;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.service.CardVault;
import dev.foliopay.service.ChargeService;
import dev.foliopay.service.support.IdempotentStore;
import io.github.resilience4j.retry.Retry;
import java.util.List;
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

  ChargeServiceImpl(ChargeRepository repository, CardVault vault, PspClient psp, IdempotentStore store, @Qualifier("pspRetry") Retry pspRetry) {
    this.repository = repository;
    this.vault = vault;
    this.psp = psp;
    this.store = store;
    this.pspRetry = pspRetry;
  }

  @Override
  public Charge charge(ChargeCommand command) {
    Money amount = command.amount().requirePositive();
    if (!vault.exists(command.cardToken())) {
      throw new InvalidRequestException("Unknown card token");
    }
    Charge charge =
        store.findOrCreate(
            () -> repository.findByIdempotencyKey(command.idempotencyKey()),
            () -> repository.saveAndFlush(new Charge(command.idempotencyKey(), command.bookingRef(), command.folioId(), command.businessDate(), command.cardToken(), amount)));
    if (!charge.matches(command.bookingRef(), command.folioId(), command.businessDate(), command.cardToken(), amount)) {
      throw new ConflictException("Idempotency key was already used with different parameters");
    }
    return charge.getStatus() == Charge.Status.PENDING ? execute(charge) : charge;
  }

  @Override
  public List<Charge> findAll() {
    return repository.findAll(org.springframework.data.domain.PageRequest.of(0, 500, org.springframework.data.domain.Sort.by("createdAt").descending())).getContent();
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
    } catch (PspDeclinedException e) {
      charge.declined(e.getMessage());
      log.warn("Charge {} declined: {}", charge.getId(), charge.getLastError());
      releaseHold(key, auth);
    } catch (PspTransientException e) {
      charge.failedAttempt(e.getMessage()); // stays PENDING: the caller retries with the same key
      log.warn("Charge {} still pending after retries: {}", charge.getId(), charge.getLastError());
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
