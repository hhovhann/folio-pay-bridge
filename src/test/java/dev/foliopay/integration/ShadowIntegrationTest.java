package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foliopay.TestBase;
import dev.foliopay.domain.model.ShadowConfig;
import dev.foliopay.service.ShadowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Shadow mode runs the same synthetic bookings through the payment flow, so it needs the whole stack. Rules are unit-tested in PricingTest. */
class ShadowIntegrationTest extends TestBase {
  @Autowired ShadowService shadow;

  @Test
  void strictNewRulesDifferFromTheLegacyStandInOnAmountAndDate() {
    var report = shadow.run(42, 200, ShadowConfig.strictNew());
    assertThat(report.errors()).isZero();
    assertThat(report.byField()).containsKeys("amount", "businessDate");
    assertThat(report.matched() + report.mismatched()).isEqualTo(200);
    assertThat(report.mismatched()).isGreaterThan(20);
  }

  @Test
  void compatibleRulesMatchTheLegacyStandInOnEveryBooking() {
    var report = shadow.run(42, 200, ShadowConfig.legacyCompatible());
    assertThat(report.mismatched()).isZero();
    assertThat(report.matchRate()).isEqualTo(1.0);
  }

  @Test
  void theSameSeedGivesTheSameBookings() {
    assertThat(shadow.generate(7, 10)).isEqualTo(shadow.generate(7, 10));
  }
}
