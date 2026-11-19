package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Webhook settings: the signing secret is never printed, and the send lease follows the timeouts")
class WebhookPropertiesTest {
  /** Fails if the signing secret can reach a log line through the settings object (a record prints every field by default). */
  @Test
  @DisplayName("The webhook signing secret is never printed")
  void theSigningSecretIsNeverPrinted() {
    WebhookProperties settings = new WebhookProperties(
        "https://hooks.example.test/folio", "whsec_test_0123456789abcdef0123456789", 12,
            Duration.ofSeconds(5), false, Duration.ofDays(30), Duration.ofSeconds(2), Duration.ofSeconds(5));

    assertThat(settings.toString()).isEqualTo("WebhookProperties[redacted]").doesNotContain("whsec_test");
  }

  /**
   * Fails if the send lease is a fixed number again: a send that outlives its lease is claimed and sent by another instance as well
   * (standards section 8: leases derived from the timeouts, agent log 32; final code review).
   */
  @Test
  @DisplayName("The send lease covers two whole sends and is never under a minute")
  void theSendLeaseCoversTwoWholeSendsAndIsNeverUnderAMinute() {
    assertThat(settings(Duration.ofSeconds(2), Duration.ofSeconds(5)).sendLease()).isEqualTo(Duration.ofMinutes(1));
    assertThat(settings(Duration.ofSeconds(20), Duration.ofSeconds(40)).sendLease()).isEqualTo(Duration.ofMinutes(2));
  }

  private static WebhookProperties settings(Duration connect, Duration read) {
    return new WebhookProperties("https://hooks.example.test/folio", "s".repeat(32), 12,
        Duration.ofSeconds(5), false, Duration.ofDays(30), connect, read);
  }
}
