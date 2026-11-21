package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OPERA settings: a business date staleness that would switch off the outage fallback is refused")
class OperaPropertiesTest {
  /** Fails if zero or a typo such as -24h starts: every card payment would fail during an OPERA outage instead of waiting to post. */
  @Test
  @DisplayName("A staleness of zero or less is refused at start-up")
  void aStalenessOfZeroOrLessIsRefused() {
    for (Duration wrong : new Duration[] {Duration.ZERO, Duration.ofHours(-24)}) {
      assertThatThrownBy(() -> new OperaProperties("H1", 1, "CC", 9001, wrong)).hasMessageContaining("business-date-max-staleness");
    }
  }

  @Test
  @DisplayName("A positive staleness is accepted")
  void aPositiveStalenessIsAccepted() {
    assertThatCode(() -> new OperaProperties("H1", 1, "CC", 9001, Duration.ofHours(24))).doesNotThrowAnyException();
  }
}
