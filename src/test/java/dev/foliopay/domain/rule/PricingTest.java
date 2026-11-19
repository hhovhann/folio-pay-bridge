package dev.foliopay.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.domain.model.ShadowBooking;
import dev.foliopay.domain.model.ShadowConfig.DateRule;
import dev.foliopay.domain.model.ShadowConfig.TaxRounding;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Pricing: rounding and the hotel's local day")
class PricingTest {
  // 3 nights at 100.05 with 7.5% tax: per night rounds 7.50375 -> 750; on the total rounds 22.51125 -> 2251
  private static final ShadowBooking BOOKING =
      new ShadowBooking("BK-1", 3, 10_005, 750, "USD", ZoneId.of("Asia/Dubai"), Instant.parse("2026-11-01T22:30:00Z"));

  @Test
  @DisplayName("Tax rounded per night differs from tax rounded on the total")
  void taxRoundedPerNightDiffersFromTaxRoundedOnTheTotal() {
    assertThat(Pricing.total(BOOKING, TaxRounding.PER_NIGHT)).isEqualTo(30_015 + 3 * 750);
    assertThat(Pricing.total(BOOKING, TaxRounding.ON_TOTAL)).isEqualTo(30_015 + 2_251);
  }

  @Test
  @DisplayName("A late evening in Dubai is the next day locally but the same day in UTC")
  void aLateEveningInDubaiIsTheNextDayLocallyButTheSameDayInUtc() {
    assertThat(Pricing.businessDate(BOOKING, DateRule.UTC_DAY)).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(Pricing.businessDate(BOOKING, DateRule.HOTEL_LOCAL_DAY)).isEqualTo(LocalDate.of(2026, 11, 2));
  }
}
