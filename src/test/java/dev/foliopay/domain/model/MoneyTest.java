package dev.foliopay.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.domain.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

class MoneyTest {
  @Test
  void keepsMinorUnitsAndCurrencyExactly() {
    Money money = new Money(12_345, "USD");
    assertThat(money.minor()).isEqualTo(12_345L);
    assertThat(money.currency()).isEqualTo("USD");
  }

  @Test
  void rejectsMalformedCurrency() {
    assertThatThrownBy(() -> new Money(100, "usd")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> new Money(100, "US")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> new Money(100, null)).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void rejectsNegativeAmounts() {
    assertThatThrownBy(() -> new Money(-1, "USD")).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void aPaymentMustBeStrictlyPositive() {
    assertThatThrownBy(() -> new Money(0, "USD").requirePositive()).isInstanceOf(InvalidRequestException.class).hasMessageContaining("positive");
    assertThat(new Money(1, "USD").requirePositive()).isEqualTo(new Money(1, "USD"));
  }
}
