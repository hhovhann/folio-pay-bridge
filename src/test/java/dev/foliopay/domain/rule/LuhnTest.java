package dev.foliopay.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LuhnTest {
  @ParameterizedTest
  @ValueSource(strings = {"4111111111111111", "4242424242424242", "5555555555554444", "378282246310005"})
  void acceptsPublicTestCards(String number) {
    assertThat(Luhn.valid(number)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"4111111111111112", "123", "", "41111111111111x1", "41111111111111111111"})
  void rejectsBadChecksumLengthAndNonDigits(String number) {
    assertThat(Luhn.valid(number)).isFalse();
  }

  @org.junit.jupiter.api.Test
  void rejectsNull() {
    assertThat(Luhn.valid(null)).isFalse();
  }
}
