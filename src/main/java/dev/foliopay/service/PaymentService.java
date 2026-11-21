package dev.foliopay.service;

import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Posting;
import java.time.LocalDate;

/** Charge the virtual card, then post to the folio. The outcome says plainly which half happened. */
public interface PaymentService {
  enum Outcome { OK, DECLINED, CHARGE_PENDING, CHARGED_NOT_POSTED }

  /** {@code clientId} and {@code businessDate}: see {@link ChargeService.ChargeCommand}. */
  record PaymentCommand(
      String clientId, String idempotencyKey, String bookingRef, String folioId, String cardToken, Money amount, LocalDate businessDate) {
    /** A payment under the bridge's own keys (a payment link, a shadow run): one namespace, whichever client relayed it. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static PaymentCommand bridge(
        String idempotencyKey, String bookingRef, String folioId, String cardToken, Money amount, LocalDate businessDate) {
      return new PaymentCommand(Charge.BRIDGE_CLIENT, idempotencyKey, bookingRef, folioId, cardToken, amount, businessDate);
    }

    /** The card token can be charged by whoever holds it (D-41): a command logged whole must not carry it. */
    @Override
    public String toString() {
      return "PaymentCommand[clientId=" + clientId + ", idempotencyKey=" + idempotencyKey + ", bookingRef=" + bookingRef + ", folioId=" + folioId
          + ", cardToken=[redacted], amount=" + amount + ", businessDate=" + businessDate + "]";
    }
  }

  record Result(Outcome outcome, Charge charge, Posting posting) {}

  Result pay(PaymentCommand command);
}
