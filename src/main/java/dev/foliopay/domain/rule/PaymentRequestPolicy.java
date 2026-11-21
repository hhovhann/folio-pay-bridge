package dev.foliopay.domain.rule;

import dev.foliopay.domain.model.BookingEvent.RatePlan;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * What to ask the guest to pay, and by when, for a booking. The deposit and how long before arrival it is due are the hotel's policy:
 * they come from configuration ({@code foliopay.payment-request.*}), so a product lead changes them without a code change.
 */
public record PaymentRequestPolicy(int depositPercent, int depositLeadDays) {
  private static final int WHOLE = 100;

  public record Decision(long amountMinor, LocalDate dueDate, String reason) {}

  public PaymentRequestPolicy {
    if (depositPercent < 1 || depositPercent > WHOLE || depositLeadDays < 1) {
      throw new IllegalStateException("foliopay.payment-request: the deposit is 1 to 100 percent, due at least one day before arrival");
    }
  }

  public Decision decide(RatePlan plan, LocalDate arrival, long totalMinor, LocalDate today) {
    if (plan == RatePlan.ADVANCE_PURCHASE) {
      return new Decision(totalMinor, today.plusDays(1), "Advance purchase: full amount within 24 hours");
    }
    long daysToArrival = ChronoUnit.DAYS.between(today, arrival);
    if (daysToArrival > depositLeadDays) {
      long deposit = Math.multiplyExact(totalMinor, (long) depositPercent) / WHOLE;
      long amount = deposit > 0 ? deposit : totalMinor; // a total too small for a deposit is asked in full, never as a zero-amount request
      return new Decision(amount, arrival.minusDays(depositLeadDays),
          "Flexible: " + depositPercent + "% deposit due " + depositLeadDays + " days before arrival");
    }
    long dueInDays = Math.min(1, Math.max(0, daysToArrival));
    return new Decision(totalMinor, today.plusDays(dueInDays), "Flexible, arriving within " + depositLeadDays + " days: full amount now");
  }
}
