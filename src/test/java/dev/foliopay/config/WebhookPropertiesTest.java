package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.Fixtures;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Webhook settings: the signing secret is never printed, and the send lease follows the timeouts")
class WebhookPropertiesTest {
  /** Fails if the signing secret can reach a log line through the settings object (a record prints every field by default). */
  @Test
  @DisplayName("The webhook signing secret is never printed")
  void theSigningSecretIsNeverPrinted() {
    WebhookProperties settings = Fixtures.webhook("https://hooks.example.test/folio", "whsec_test_0123456789abcdef0123456789", 12);

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

  /** Fails if the back-off is fixed in code again: an operator who sets a slower one for a fragile receiver must get it. */
  @Test
  @DisplayName("The delivery back-off comes from the settings: it doubles from the first retry up to the longest wait")
  void theDeliveryBackOffComesFromTheSettings() {
    WebhookProperties slow = new WebhookProperties("https://hooks.example.test/folio", "s".repeat(32), 5, Duration.ofSeconds(5), false,
        Duration.ofDays(30), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(2), Duration.ofMinutes(5), 10);

    assertThat(slow.schedule().maxAttempts()).isEqualTo(5);
    assertThat(slow.schedule().waitAfter(1)).isEqualTo(Duration.ofMinutes(2));
    assertThat(slow.schedule().waitAfter(2)).isEqualTo(Duration.ofMinutes(4));
    assertThat(slow.schedule().waitAfter(3)).as("capped at the longest wait").isEqualTo(Duration.ofMinutes(5));
    assertThat(slow.schedule().waitAfter(60)).as("no overflow after many failures").isEqualTo(Duration.ofMinutes(5));
    assertThat(slow.schedule().waitAfter(0)).as("never a negative wait").isEqualTo(Duration.ofMinutes(2));
  }

  /** Fails if a schedule that can never send starts: a batch of none would throw on every relay run, and no webhook would ever leave. */
  @Test
  @DisplayName("A delivery schedule that could never send stops the start, naming the settings")
  void aDeliveryScheduleThatCouldNeverSendStopsTheStart() {
    for (WebhookProperties wrong : List.of(
        schedule(Duration.ofSeconds(30), Duration.ofHours(1), 0), schedule(Duration.ofSeconds(30), Duration.ofHours(1), 1_001),
        schedule(Duration.ZERO, Duration.ofHours(1), 100), schedule(Duration.ofHours(2), Duration.ofHours(1), 100))) {
      assertThatThrownBy(wrong::requireUsable).isInstanceOf(IllegalStateException.class).hasMessageContaining("foliopay.webhook");
    }
    assertThatCode(() -> schedule(Duration.ofSeconds(30), Duration.ofHours(1), 100).requireUsable()).doesNotThrowAnyException();
  }

  private static WebhookProperties schedule(Duration firstRetry, Duration longestWait, int batchSize) {
    return new WebhookProperties("https://hooks.example.test/folio", "s".repeat(32), 12, Duration.ofSeconds(5), false,
        Duration.ofDays(30), Duration.ofSeconds(2), Duration.ofSeconds(5), firstRetry, longestWait, batchSize);
  }

  private static WebhookProperties settings(Duration connect, Duration read) {
    return new WebhookProperties("https://hooks.example.test/folio", "s".repeat(32), 12,
        Duration.ofSeconds(5), false, Duration.ofDays(30), connect, read, Duration.ofSeconds(30), Duration.ofHours(1), 100);
  }
}
