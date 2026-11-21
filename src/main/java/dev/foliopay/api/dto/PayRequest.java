package dev.foliopay.api.dto;

import dev.foliopay.domain.model.Money;
import dev.foliopay.service.PaymentService.PaymentCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record PayRequest(
    @Schema(description = "Client-chosen key, unique per client. Repeating it returns the first result; never charges twice.", example = "order-7731-attempt-1")
    @NotBlank @Size(max = 100) @Pattern(regexp = "(?!link:|post:|shadow-)[A-Za-z0-9:_.-]+", message = "must be letters, digits and : _ . - and not start with a reserved prefix") String idempotencyKey,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String bookingRef,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String folioId,
    @NotBlank @Pattern(regexp = "tok_[a-f0-9]{32}") String token,
    @Schema(description = "Amount in minor units (cents)", example = "12345") @Min(1) @Max(Money.MAX_MINOR) long amountMinor,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
    @Schema(description = "Optional. Must equal the hotel's current business date from the PMS; when omitted, a new charge takes the PMS date and a repeat keeps the date it was taken on") LocalDate businessDate) {
  /** {@code clientId} is the authenticated caller: idempotency keys are per client (D-40). */
  public PaymentCommand toCommand(String clientId, LocalDate checkedBusinessDate) {
    return new PaymentCommand(clientId, idempotencyKey, bookingRef, folioId, token, new Money(amountMinor, currency), checkedBusinessDate);
  }

  /** The card token can be charged by whoever holds it (D-41): a request logged whole must not carry it. */
  @Override
  public String toString() {
    return "PayRequest[idempotencyKey=" + idempotencyKey + ", bookingRef=" + bookingRef + ", folioId=" + folioId + ", token=[redacted], amountMinor="
        + amountMinor + ", currency=" + currency + ", businessDate=" + businessDate + "]";
  }
}
