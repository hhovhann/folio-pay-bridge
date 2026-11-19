package dev.foliopay.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.domain.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Money: minor units and a currency, exact")
class MoneyTest {
  @Test
  @DisplayName("Keeps minor units and currency exactly")
  void keepsMinorUnitsAndCurrencyExactly() {
    Money money = new Money(12_345, "USD");
    assertThat(money.minor()).isEqualTo(12_345L);
    assertThat(money.currency()).isEqualTo("USD");
  }

  @Test
  @DisplayName("Rejects a malformed currency")
  void rejectsMalformedCurrency() {
    assertThatThrownBy(() -> new Money(100, "usd")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> new Money(100, "US")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> new Money(100, null)).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  @DisplayName("Rejects negative amounts")
  void rejectsNegativeAmounts() {
    assertThatThrownBy(() -> new Money(-1, "USD")).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  @DisplayName("A payment must be strictly positive")
  void aPaymentMustBeStrictlyPositive() {
    assertThatThrownBy(() -> new Money(0, "USD").requirePositive()).isInstanceOf(InvalidRequestException.class).hasMessageContaining("positive");
    assertThat(new Money(1, "USD").requirePositive()).isEqualTo(new Money(1, "USD"));
  }
}
