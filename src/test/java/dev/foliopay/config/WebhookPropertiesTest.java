package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Webhook settings: the signing secret is never printed")
class WebhookPropertiesTest {
  /** Fails if the signing secret can reach a log line through the settings object (a record prints every field by default). */
  @Test
  @DisplayName("The webhook signing secret is never printed")
  void theSigningSecretIsNeverPrinted() {
    WebhookProperties settings = new WebhookProperties(
        "https://hooks.example.test/folio", "whsec_test_0123456789abcdef0123456789", 12, Duration.ofSeconds(5), false, Duration.ofDays(30));

    assertThat(settings.toString()).isEqualTo("WebhookProperties[redacted]").doesNotContain("whsec_test");
  }
}
