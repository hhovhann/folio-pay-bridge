package dev.foliopay.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.domain.model.BookingEvent.RatePlan;
import dev.foliopay.domain.rule.PaymentRequestPolicy.Decision;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PaymentRequestPolicyTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

  @Test
  void advancePurchaseIsFullAmountDueTomorrow() {
    Decision d = PaymentRequestPolicy.decide(RatePlan.ADVANCE_PURCHASE, TODAY.plusDays(40), 100_000, TODAY);
    assertThat(d.amountMinor()).isEqualTo(100_000);
    assertThat(d.dueDate()).isEqualTo(TODAY.plusDays(1));
  }

  @Test
  void flexibleFarAheadIsATwentyPercentDepositDueAWeekBeforeArrival() {
    Decision d = PaymentRequestPolicy.decide(RatePlan.FLEXIBLE, TODAY.plusDays(30), 100_000, TODAY);
    assertThat(d.amountMinor()).isEqualTo(20_000);
    assertThat(d.dueDate()).isEqualTo(TODAY.plusDays(23));
  }

  @Test
  void flexibleWithinAWeekIsFullAmountNow() {
    assertThat(PaymentRequestPolicy.decide(RatePlan.FLEXIBLE, TODAY.plusDays(3), 100_000, TODAY).amountMinor()).isEqualTo(100_000);
  }

  @Test
  void theWeekBoundaryIsExclusive() {
    assertThat(PaymentRequestPolicy.decide(RatePlan.FLEXIBLE, TODAY.plusDays(7), 100_000, TODAY).amountMinor()).isEqualTo(100_000);
    assertThat(PaymentRequestPolicy.decide(RatePlan.FLEXIBLE, TODAY.plusDays(8), 100_000, TODAY).amountMinor()).isEqualTo(20_000);
  }

  @Test
  void aHugeTotalCannotOverflowTheDepositIntoANonsenseAmount() {
    long max = dev.foliopay.domain.model.Money.MAX_MINOR;
    assertThat(PaymentRequestPolicy.decide(RatePlan.FLEXIBLE, TODAY.plusDays(30), max, TODAY).amountMinor()).isEqualTo(max / 5);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> PaymentRequestPolicy.decide(RatePlan.FLEXIBLE, TODAY.plusDays(30), Long.MAX_VALUE, TODAY))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void aTotalTooSmallForADepositIsAskedInFullNeverAsAZeroAmountRequest() {
    assertThat(PaymentRequestPolicy.decide(RatePlan.FLEXIBLE, TODAY.plusDays(30), 4, TODAY).amountMinor()).isEqualTo(4);
  }

  @Test
  void arrivingTodayIsDueToday() {
    assertThat(PaymentRequestPolicy.decide(RatePlan.FLEXIBLE, TODAY, 100_000, TODAY).dueDate()).isEqualTo(TODAY);
  }
}
