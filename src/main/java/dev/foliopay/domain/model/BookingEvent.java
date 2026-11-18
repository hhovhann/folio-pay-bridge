package dev.foliopay.domain.model;

import java.time.LocalDate;

/** A booking change announced by the PMS. */
public record BookingEvent(Type type, String bookingRef, String folioId, RatePlan ratePlan, LocalDate arrival, Money total) {
  public enum Type { NEW, MODIFIED, CANCELLED }

  public enum RatePlan { ADVANCE_PURCHASE, FLEXIBLE }
}
