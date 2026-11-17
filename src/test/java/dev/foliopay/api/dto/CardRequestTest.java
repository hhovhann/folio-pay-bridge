package dev.foliopay.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CardRequestTest {
  @Test
  void aCardNumberNeverAppearsWhenTheRequestObjectIsPrinted() {
    assertThat(new CardRequest("4111111111111111", "12/29").toString()).isEqualTo("CardRequest[redacted]").doesNotContain("4111").doesNotContain("12/29");
  }
}
