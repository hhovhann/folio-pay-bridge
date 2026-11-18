package dev.foliopay.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.domain.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

class MoneyFormatTest {
  @Test
  void formatsEachCurrencyWithItsOwnDecimals() {
    assertThat(new Money(12_345, "USD").toDecimalString()).isEqualTo("123.45");
    assertThat(new Money(1_234, "KWD").toDecimalString()).as("Kuwaiti dinar has three decimals").isEqualTo("1.234");
    assertThat(new Money(500, "JPY").toDecimalString()).as("yen has none").isEqualTo("500");
    assertThat(new Money(5, "USD").toDecimalString()).isEqualTo("0.05");
    assertThat(new Money(0, "KWD").toDecimalString()).isEqualTo("0.000");
  }

  @Test
  void refusesACurrencyCodeThatIsNotACurrency() {
    assertThatThrownBy(() -> new Money(100, "ZZZ")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> new Money(100, "XXX")).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void refusesAnAmountAboveTheCeilingSoArithmeticCannotOverflow() {
    assertThat(new Money(Money.MAX_MINOR, "USD").minor()).isEqualTo(Money.MAX_MINOR);
    assertThatThrownBy(() -> new Money(Money.MAX_MINOR + 1, "USD")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> new Money(Long.MAX_VALUE, "USD")).isInstanceOf(InvalidRequestException.class);
  }
}
