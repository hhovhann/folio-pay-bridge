package dev.foliopay.service;

import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import java.time.LocalDate;
import java.util.List;

/** Charges a tokenised virtual card, exactly once per idempotency key. */
public interface ChargeService {
  record ChargeCommand(String idempotencyKey, String bookingRef, String folioId, LocalDate businessDate, String cardToken, Money amount) {}

  /** Creates or resumes the charge. A decline is final; a transient failure leaves it PENDING so the same key can be retried. */
  Charge charge(ChargeCommand command);

  List<Charge> findAll();
}
