package dev.foliopay.api.dto;

import dev.foliopay.domain.model.BookingEvent;
import dev.foliopay.domain.model.BookingEvent.RatePlan;
import dev.foliopay.domain.model.Money;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public record BookingEventRequest(
    @NotNull BookingEvent.Type type,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String bookingRef,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String folioId,
    @NotNull RatePlan ratePlan,
    @NotNull LocalDate arrival,
    @Min(1) @Max(Money.MAX_MINOR) long totalMinor,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
    LocalDate today) {
  public BookingEvent toEvent() {
    return new BookingEvent(type, bookingRef, folioId, ratePlan, arrival, new Money(totalMinor, currency));
  }
}
