package dev.foliopay.service;

import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Posting;
import java.time.LocalDate;

/** Charge the virtual card, then post to the folio. The outcome says plainly which half happened. */
public interface PaymentService {
  enum Outcome { OK, DECLINED, CHARGE_PENDING, CHARGED_NOT_POSTED }

  /** {@code businessDate} may be null: see {@link ChargeService.ChargeCommand}. */
  record PaymentCommand(String idempotencyKey, String bookingRef, String folioId, String cardToken, Money amount, LocalDate businessDate) {}

  record Result(Outcome outcome, Charge charge, Posting posting) {}

  Result pay(PaymentCommand command);
}
