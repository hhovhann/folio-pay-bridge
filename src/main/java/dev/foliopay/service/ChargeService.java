package dev.foliopay.service;

import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Charges a tokenised virtual card, exactly once per client and idempotency key. */
public interface ChargeService {
  /**
   * {@code clientId} is whose key it is (D-40): {@link Charge#BRIDGE_CLIENT} for the bridge's own keys. {@code businessDate} may be null:
   * a new charge then takes the PMS's current business date, and a repeat keeps the date the charge was taken on. A date that is given
   * must match the stored one on a repeat.
   */
  record ChargeCommand(
      String clientId, String idempotencyKey, String bookingRef, String folioId, LocalDate businessDate, String cardToken, Money amount) {
    /** The card token can be charged by whoever holds it (D-41): a command logged whole must not carry it. */
    @Override
    public String toString() {
      return "ChargeCommand[clientId=" + clientId + ", idempotencyKey=" + idempotencyKey + ", bookingRef=" + bookingRef + ", folioId=" + folioId
          + ", businessDate=" + businessDate + ", cardToken=[redacted], amount=" + amount + "]";
    }
  }

  /** Creates or resumes the charge. A decline is final; a transient failure leaves it PENDING so the same key can be retried. */
  Charge charge(ChargeCommand command);

  /** The charge stored under a client's key, if any: how a caller learns whether money may have moved after a request failed. */
  Optional<Charge> find(String clientId, String idempotencyKey);

  List<Charge> findAll();
}
