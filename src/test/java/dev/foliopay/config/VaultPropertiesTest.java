package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Card retention settings: a period short enough to purge cards in use is refused")
class VaultPropertiesTest {
  @Test
  @DisplayName("A retention period under a day is refused")
  void aRetentionPeriodUnderADayIsRefused() {
    assertThatThrownBy(() -> new VaultProperties(Duration.ofHours(23), Duration.ofHours(1), 100))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("foliopay.vault.retention");
  }

  @Test
  @DisplayName("A day or more is accepted")
  void aDayOrMoreIsAccepted() {
    assertThatCode(() -> new VaultProperties(Duration.ofDays(1), Duration.ofHours(1), 100)).doesNotThrowAnyException();
    assertThatCode(() -> new VaultProperties(Duration.ofDays(90), Duration.ofHours(1), 100)).doesNotThrowAnyException();
  }
}
