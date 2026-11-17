package dev.foliopay.api.dto;

import dev.foliopay.domain.model.Charge;
import java.time.Instant;

/** What a client may see of a charge. The card token is deliberately left out. */
public record ChargeView(String id, String idempotencyKey, String bookingRef, long amountMinor, String currency, Charge.Status status, String pspRef, String lastError, int attempts, Instant createdAt) {
  public static ChargeView from(Charge c) {
    return new ChargeView(c.getId(), c.getIdempotencyKey(), c.getBookingRef(), c.getAmountMinor(), c.getCurrency(), c.getStatus(), c.getPspRef(), c.getLastError(), c.getAttempts(), c.getCreatedAt());
  }
}
