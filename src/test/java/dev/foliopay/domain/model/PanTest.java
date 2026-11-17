package dev.foliopay.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.domain.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

class PanTest {
  private static final String VISA_TEST_CARD = "4111111111111111";

  @Test
  void stripsSpacesAndDashes() {
    assertThat(new Pan("4111 1111-1111 1111").value()).isEqualTo(VISA_TEST_CARD);
  }

  @Test
  void exposesOnlyBinAndLastFour() {
    Pan pan = new Pan(VISA_TEST_CARD);
    assertThat(pan.bin6()).isEqualTo("411111");
    assertThat(pan.last4()).isEqualTo("1111");
  }

  @Test
  void toStringNeverContainsMoreThanTheLastFour() {
    assertThat(new Pan(VISA_TEST_CARD).toString()).isEqualTo("Pan[**** 1111]").doesNotContain("4111");
  }

  @Test
  void refusesNumbersThatFailTheLuhnCheck() {
    assertThatThrownBy(() -> new Pan("4111111111111112")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> new Pan(null)).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void theErrorMessageDoesNotEchoTheSubmittedNumber() {
    assertThatThrownBy(() -> new Pan("4111111111111112")).message().doesNotContain("4111");
  }
}
