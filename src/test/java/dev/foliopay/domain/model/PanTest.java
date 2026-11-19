package dev.foliopay.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.domain.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Card number: normalised, masked and Luhn-checked")
class PanTest {
  private static final String VISA_TEST_CARD = "4111111111111111";

  @Test
  @DisplayName("Strips spaces and dashes from a card number")
  void stripsSpacesAndDashes() {
    assertThat(new Pan("4111 1111-1111 1111").value()).isEqualTo(VISA_TEST_CARD);
  }

  @Test
  @DisplayName("Exposes only the BIN and the last four digits")
  void exposesOnlyBinAndLastFour() {
    Pan pan = new Pan(VISA_TEST_CARD);
    assertThat(pan.bin6()).isEqualTo("411111");
    assertThat(pan.last4()).isEqualTo("1111");
  }

  @Test
  @DisplayName("toString never contains more than the last four digits")
  void toStringNeverContainsMoreThanTheLastFour() {
    assertThat(new Pan(VISA_TEST_CARD).toString()).isEqualTo("Pan[**** 1111]").doesNotContain("4111");
  }

  @Test
  @DisplayName("Refuses numbers that fail the Luhn check")
  void refusesNumbersThatFailTheLuhnCheck() {
    assertThatThrownBy(() -> new Pan("4111111111111112")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> new Pan(null)).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  @DisplayName("The error message does not echo the submitted number")
  void theErrorMessageDoesNotEchoTheSubmittedNumber() {
    assertThatThrownBy(() -> new Pan("4111111111111112")).message().doesNotContain("4111");
  }
}
