package dev.foliopay.domain.rule;

import dev.foliopay.domain.model.BookingEvent.RatePlan;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** What to ask the guest to pay, and by when, for a booking. Written down so a product lead can change it. */
public final class PaymentRequestPolicy {
  private static final int DEPOSIT_PERCENT = 20;
  private static final int DEPOSIT_LEAD_DAYS = 7;

  public record Decision(long amountMinor, LocalDate dueDate, String reason) {}

  private PaymentRequestPolicy() {}

  public static Decision decide(RatePlan plan, LocalDate arrival, long totalMinor, LocalDate today) {
    if (plan == RatePlan.ADVANCE_PURCHASE) {
      return new Decision(totalMinor, today.plusDays(1), "Advance purchase: full amount within 24 hours");
    }
    long daysToArrival = ChronoUnit.DAYS.between(today, arrival);
    if (daysToArrival > DEPOSIT_LEAD_DAYS) {
      long deposit = Math.multiplyExact(totalMinor, (long) DEPOSIT_PERCENT) / 100;
      long amount = deposit > 0 ? deposit : totalMinor; // a total too small for a deposit is asked in full, never as a zero-amount request
      return new Decision(amount, arrival.minusDays(DEPOSIT_LEAD_DAYS), "Flexible: 20% deposit due 7 days before arrival");
    }
    long dueInDays = Math.min(1, Math.max(0, daysToArrival));
    return new Decision(totalMinor, today.plusDays(dueInDays), "Flexible, arriving within 7 days: full amount now");
  }
}
