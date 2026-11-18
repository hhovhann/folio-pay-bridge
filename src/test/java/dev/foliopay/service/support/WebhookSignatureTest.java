package dev.foliopay.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookSignatureTest {
  /** The expected value was computed outside Java (Python's hmac module), so a receiver in any language gets the same answer. */
  @Test
  void signsTheTimestampAndTheBodyWithHmacSha256() {
    assertThat(WebhookSignature.sign("whsec_test_0123456789abcdef0123456789", 1_700_000_000L, "{\"id\":\"payment.captured:c1\"}"))
        .isEqualTo("v1=a52eb2dfc3d8e1204e4451cf6f4ad72924156ed7a96c4cbec8d23b00ab0470ba");
  }

  @Test
  void aChangedBodyOrTimestampGivesADifferentSignatureSoNeitherCanBeReplayedOrAltered() {
    String original = WebhookSignature.sign("s".repeat(32), 1, "{\"amountMinor\":100}");
    assertThat(WebhookSignature.sign("s".repeat(32), 1, "{\"amountMinor\":900}")).isNotEqualTo(original);
    assertThat(WebhookSignature.sign("s".repeat(32), 2, "{\"amountMinor\":100}")).isNotEqualTo(original);
  }
}
