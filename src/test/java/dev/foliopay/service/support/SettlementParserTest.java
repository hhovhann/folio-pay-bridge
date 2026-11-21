package dev.foliopay.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.model.SettlementLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Settlement file parser: strict and safe")
class SettlementParserTest {
  private static final String HEADER = "psp_ref,amount_minor,currency\n";

  @Test
  @DisplayName("Reads lines and skips blank ones")
  void readsLinesAndSkipsBlankOnes() {
    assertThat(SettlementParser.parse(HEADER + "cap_1,1000,USD\n\n cap_2 , 250 , KWD \n"))
        .containsExactly(new SettlementLine("cap_1", 1000, "USD"), new SettlementLine("cap_2", 250, "KWD"));
  }

  @Test
  @DisplayName("A wrong header fails loudly")
  void aWrongHeaderFailsLoudly() {
    assertThatThrownBy(() -> SettlementParser.parse("ref,amount\ncap_1,1000")).isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("Header");
  }

  @Test
  @DisplayName("A malformed row names its line number")
  void aMalformedRowNamesItsLineNumber() {
    assertThatThrownBy(() -> SettlementParser.parse(HEADER + "cap_1,1000,USD\ncap_2")).hasMessageContaining("Row 3");
  }

  @Test
  @DisplayName("A bad amount is not silently skipped")
  void aBadAmountIsNotSilentlySkipped() {
    assertThatThrownBy(() -> SettlementParser.parse(HEADER + "cap_1,12.50,USD")).hasMessageContaining("bad amount");
  }

  @Test
  @DisplayName("A negative amount is not accepted as a settlement")
  void aNegativeAmountIsNotAcceptedAsASettlement() {
    assertThatThrownBy(() -> SettlementParser.parse(HEADER + "cap_1,-500,USD")).hasMessageContaining("Row 2");
  }

  @Test
  @DisplayName("An unknown currency is refused")
  void anUnknownCurrencyIsRefused() {
    assertThatThrownBy(() -> SettlementParser.parse(HEADER + "cap_1,500,ZZZ")).hasMessageContaining("Row 2");
  }

  @Test
  @DisplayName("A reference that could run as a spreadsheet formula is refused")
  void aReferenceThatCouldRunAsASpreadsheetFormulaIsRefused() {
    assertThatThrownBy(() -> SettlementParser.parse(HEADER + "=HYPERLINK(\"http://evil\"),500,USD")).hasMessageContaining("Row 2");
    assertThatThrownBy(() -> SettlementParser.parse(HEADER + "+cmd|' /C calc'!A0,500,USD")).hasMessageContaining("Row 2");
  }
}
