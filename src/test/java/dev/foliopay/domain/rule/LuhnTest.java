package dev.foliopay.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Luhn check: public test cards pass, bad numbers do not")
class LuhnTest {
  @ParameterizedTest
  @DisplayName("Accepts public test card numbers")
  @ValueSource(strings = {"4111111111111111", "4242424242424242", "5555555555554444", "378282246310005"})
  void acceptsPublicTestCards(String number) {
    assertThat(Luhn.valid(number)).isTrue();
  }

  @ParameterizedTest
  @DisplayName("Rejects a bad checksum, a bad length and non-digits")
  @ValueSource(strings = {"4111111111111112", "123", "", "41111111111111x1", "41111111111111111111"})
  void rejectsBadChecksumLengthAndNonDigits(String number) {
    assertThat(Luhn.valid(number)).isFalse();
  }

  @Test
  @DisplayName("A missing number is not valid")
  void rejectsNull() {
    assertThat(Luhn.valid(null)).isFalse();
  }
}
