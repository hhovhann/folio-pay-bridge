package dev.foliopay.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.domain.model.BookingEvent.RatePlan;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.rule.PaymentRequestPolicy.Decision;
import java.time.LocalDate;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Payment request policy: how much is asked, and when, for each rate plan")
class PaymentRequestPolicyTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
  /** The policy application.properties ships with: 20% due 7 days before arrival. */
  private static final PaymentRequestPolicy POLICY = new PaymentRequestPolicy(20, 7);

  @Test
  @DisplayName("Advance purchase: the full amount, due tomorrow")
  void advancePurchaseIsFullAmountDueTomorrow() {
    Decision d = POLICY.decide(RatePlan.ADVANCE_PURCHASE, TODAY.plusDays(40), 100_000, TODAY);
    assertThat(d.amountMinor()).isEqualTo(100_000);
    assertThat(d.dueDate()).isEqualTo(TODAY.plusDays(1));
  }

  @Test
  @DisplayName("Flexible, booked far ahead: a 20% deposit, due a week before arrival")
  void flexibleFarAheadIsATwentyPercentDepositDueAWeekBeforeArrival() {
    Decision d = POLICY.decide(RatePlan.FLEXIBLE, TODAY.plusDays(30), 100_000, TODAY);
    assertThat(d.amountMinor()).isEqualTo(20_000);
    assertThat(d.dueDate()).isEqualTo(TODAY.plusDays(23));
  }

  @Test
  @DisplayName("Flexible, within a week of arrival: the full amount, now")
  void flexibleWithinAWeekIsFullAmountNow() {
    assertThat(POLICY.decide(RatePlan.FLEXIBLE, TODAY.plusDays(3), 100_000, TODAY).amountMinor()).isEqualTo(100_000);
  }

  @Test
  @DisplayName("The one-week boundary is exclusive")
  void theWeekBoundaryIsExclusive() {
    assertThat(POLICY.decide(RatePlan.FLEXIBLE, TODAY.plusDays(7), 100_000, TODAY).amountMinor()).isEqualTo(100_000);
    assertThat(POLICY.decide(RatePlan.FLEXIBLE, TODAY.plusDays(8), 100_000, TODAY).amountMinor()).isEqualTo(20_000);
  }

  @Test
  @DisplayName("A huge total cannot overflow the deposit into a nonsense amount")
  void aHugeTotalCannotOverflowTheDepositIntoANonsenseAmount() {
    long max = Money.MAX_MINOR;
    assertThat(POLICY.decide(RatePlan.FLEXIBLE, TODAY.plusDays(30), max, TODAY).amountMinor()).isEqualTo(max / 5);
    Assertions.assertThatThrownBy(
            () -> POLICY.decide(RatePlan.FLEXIBLE, TODAY.plusDays(30), Long.MAX_VALUE, TODAY))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  @DisplayName("A total too small for a deposit is asked in full, never as a zero-amount request")
  void aTotalTooSmallForADepositIsAskedInFullNeverAsAZeroAmountRequest() {
    assertThat(POLICY.decide(RatePlan.FLEXIBLE, TODAY.plusDays(30), 4, TODAY).amountMinor()).isEqualTo(4);
  }

  @Test
  @DisplayName("Arriving today: due today")
  void arrivingTodayIsDueToday() {
    assertThat(POLICY.decide(RatePlan.FLEXIBLE, TODAY, 100_000, TODAY).dueDate()).isEqualTo(TODAY);
  }

  /** Fails if a value is still hardcoded: a hotel that asks 30% two weeks ahead must get exactly that, and be told so. */
  @Test
  @DisplayName("Another hotel's policy is applied as configured, in the amount, the due date and the reason given")
  void anotherHotelsPolicyIsAppliedAsConfigured() {
    Decision d = new PaymentRequestPolicy(30, 14).decide(RatePlan.FLEXIBLE, TODAY.plusDays(30), 100_000, TODAY);

    assertThat(d.amountMinor()).isEqualTo(30_000);
    assertThat(d.dueDate()).isEqualTo(TODAY.plusDays(16));
    assertThat(d.reason()).isEqualTo("Flexible: 30% deposit due 14 days before arrival");
    assertThat(new PaymentRequestPolicy(30, 14).decide(RatePlan.FLEXIBLE, TODAY.plusDays(10), 100_000, TODAY).amountMinor())
        .as("inside its own 14-day window, not the default 7").isEqualTo(100_000);
  }

  @ParameterizedTest(name = "{0}% due {1} days before arrival")
  @CsvSource({"0, 7", "101, 7", "20, 0"})
  @DisplayName("A policy that asks nothing, more than everything, or on the day of arrival is refused at start-up")
  void aPolicyOutsideItsBoundsIsRefused(int percent, int leadDays) {
    Assertions.assertThatThrownBy(() -> new PaymentRequestPolicy(percent, leadDays)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("foliopay.payment-request");
  }
}
