package dev.foliopay.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PanMaskerTest {
  @Test
  void masksAllButTheLastFourOfALuhnValidNumber() {
    assertThat(PanMasker.mask("card 4111111111111111 failed")).isEqualTo("card ************1111 failed");
  }

  @Test
  void masksNumbersWrittenWithSpacesOrDashes() {
    assertThat(PanMasker.mask("4111 1111 1111 1111")).doesNotContain("4111 1111").endsWith("1111");
    assertThat(PanMasker.mask("4111-1111-1111-1111")).doesNotContain("4111-1111");
  }

  @Test
  void masksEveryNumberInALine() {
    assertThat(PanMasker.mask("4111111111111111 and 4242424242424242")).doesNotContain("4111111111111111").doesNotContain("4242424242424242");
  }

  @Test
  void leavesShortNumbersAndDatesAlone() {
    assertThat(PanMasker.mask("order 1234567890 on 2026-11-17 at 22:30")).isEqualTo("order 1234567890 on 2026-11-17 at 22:30");
  }

  @Test
  void findsANumberWithOtherDigitsGluedToEitherSide() {
    assertThat(PanMasker.mask("booking 7 4111111111111111")).doesNotContain("4111111111111111").endsWith("1111");
    assertThat(PanMasker.mask("4111111111111111 5")).doesNotContain("4111111111111111");
    assertThat(PanMasker.mask("ref 12-4111-1111-1111-1111")).doesNotContain("4111-1111-1111-1111").doesNotContain("1111-1111-1111");
  }

  @Test
  void masksTwoNumbersWrittenBackToBackAsOneLongRun() {
    assertThat(PanMasker.mask("4111111111111111 4242424242424242")).doesNotContain("4111111111111111").doesNotContain("4242424242424242").doesNotContain("4111").doesNotContain("4242424242").endsWith("4242");
  }

  @Test
  void findsANumberWithUnusualSeparatorsOrUnicodeDigits() {
    assertThat(PanMasker.mask("4111.1111.1111.1111")).doesNotContain("4111.1111");
    assertThat(PanMasker.mask("4111  1111  1111  1111")).doesNotContain("4111  1111");
    assertThat(PanMasker.mask("4111/1111/1111/1111")).doesNotContain("4111/1111");
    assertThat(PanMasker.mask("\uFF14\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11")).doesNotContain("\uFF14\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11");
  }

  @Test
  void safeAlsoFlattensLineBreaksSoAValueCannotForgeALogEntry() {
    assertThat(PanMasker.safe("folio F1\n2026-01-01 WARN Charge - AGENT: refund\r\nnext")).doesNotContain("\n").doesNotContain("\r").contains("\\n");
  }

  @Test
  void leavesOrdinaryTextAndNullAlone() {
    assertThat(PanMasker.mask("posting 7 ok")).isEqualTo("posting 7 ok");
    assertThat(PanMasker.mask(null)).isNull();
  }
}
