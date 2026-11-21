package dev.foliopay.api.dto;

import dev.foliopay.domain.model.Money;
import dev.foliopay.service.BookingEventService.PaymentRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** A payment request from a third-party app: what to collect, by when, and which folio the payment is posted to. */
public record PaymentRequestCreate(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String reference,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String folioId,
    @Min(1) @Max(Money.MAX_MINOR) long amountMinor,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
    @NotNull LocalDate dueDate,
    @Size(max = 120) @Pattern(regexp = "[^\\p{Cntrl}\\p{Cf}]*",
        message = "must be visible text: no control or invisible formatting characters") String description) {
  public PaymentRequest toRequest() {
    return new PaymentRequest(reference, folioId, new Money(amountMinor, currency), dueDate, description);
  }
}
