package dev.foliopay.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Card request: never prints a card number")
class CardRequestTest {
  @Test
  @DisplayName("A card number never appears when the request object is printed")
  void aCardNumberNeverAppearsWhenTheRequestObjectIsPrinted() {
    assertThat(new CardRequest("4111111111111111", "12/29").toString()).isEqualTo("CardRequest[redacted]").doesNotContain("4111").doesNotContain("12/29");
  }
}
