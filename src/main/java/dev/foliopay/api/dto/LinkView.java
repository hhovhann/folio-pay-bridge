package dev.foliopay.api.dto;

import dev.foliopay.domain.model.PaymentLink;
import java.time.LocalDate;

public record LinkView(String id, String bookingRef, String folioId, long amountMinor, String currency, LocalDate dueDate, String reason,
    PaymentLink.Status status) {
  public static LinkView from(PaymentLink l) {
    return new LinkView(l.getId(), l.getBookingRef(), l.getFolioId(), l.getAmountMinor(), l.getCurrency(), l.getDueDate(), l.getReason(),
        l.getStatus());
  }
}
