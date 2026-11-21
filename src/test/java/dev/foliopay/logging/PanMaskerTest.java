package dev.foliopay.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Card number mask: what the log mask finds and what it leaves alone")
class PanMaskerTest {
  @Test
  @DisplayName("Masks all but the last four digits of a Luhn-valid number")
  void masksAllButTheLastFourOfALuhnValidNumber() {
    assertThat(PanMasker.mask("card 4111111111111111 failed")).isEqualTo("card ************1111 failed");
  }

  @Test
  @DisplayName("Masks numbers written with spaces or dashes")
  void masksNumbersWrittenWithSpacesOrDashes() {
    assertThat(PanMasker.mask("4111 1111 1111 1111")).doesNotContain("4111 1111").endsWith("1111");
    assertThat(PanMasker.mask("4111-1111-1111-1111")).doesNotContain("4111-1111");
  }

  @Test
  @DisplayName("Masks every card number in a line")
  void masksEveryNumberInALine() {
    assertThat(PanMasker.mask("4111111111111111 and 4242424242424242")).doesNotContain("4111111111111111").doesNotContain("4242424242424242");
  }

  @Test
  @DisplayName("Leaves short numbers and dates alone")
  void leavesShortNumbersAndDatesAlone() {
    assertThat(PanMasker.mask("order 1234567890 on 2026-11-17 at 22:30")).isEqualTo("order 1234567890 on 2026-11-17 at 22:30");
  }

  @Test
  @DisplayName("Finds a card number with other digits glued to either side")
  void findsANumberWithOtherDigitsGluedToEitherSide() {
    assertThat(PanMasker.mask("booking 7 4111111111111111")).doesNotContain("4111111111111111").endsWith("1111");
    assertThat(PanMasker.mask("4111111111111111 5")).doesNotContain("4111111111111111");
    assertThat(PanMasker.mask("ref 12-4111-1111-1111-1111")).doesNotContain("4111-1111-1111-1111").doesNotContain("1111-1111-1111");
  }

  @Test
  @DisplayName("Masks two numbers written back to back as one long run")
  void masksTwoNumbersWrittenBackToBackAsOneLongRun() {
    assertThat(PanMasker.mask("4111111111111111 4242424242424242")).doesNotContain("4111111111111111").doesNotContain("4242424242424242").doesNotContain("4111").doesNotContain("4242424242").endsWith("4242");
  }

  /** Review finding M5: a separator of three or four characters used to hide a number from the masker. */
  @Test
  @DisplayName("Finds a card number with separators of three or four characters")
  void findsANumberWithSeparatorsOfThreeOrFourCharacters() {
    assertThat(PanMasker.mask("4111 - 1111 - 1111 - 1111")).doesNotContain("4111 - 1111").endsWith("1111");
    assertThat(PanMasker.mask("4111\t\t\t1111111111111")).doesNotContain("4111\t\t\t1111");
    assertThat(PanMasker.mask("[\"4111\", \"1111\", \"1111\", \"1111\"]")).as("a JSON array of groups").doesNotContain("\"4111\", \"1111\"");
  }

  @Test
  @DisplayName("Still treats words and line breaks as boundaries")
  void stillLeavesWordsAndLineBreaksAsBoundaries() {
    assertThat(PanMasker.mask("4111 1111 and 1111 1111")).as("a word is not a separator").isEqualTo("4111 1111 and 1111 1111");
    assertThat(PanMasker.mask("4111111\n111111111")).as("a line break is not a separator").isEqualTo("4111111\n111111111");
  }

  @Test
  @DisplayName("Finds a card number with unusual separators or Unicode digits")
  void findsANumberWithUnusualSeparatorsOrUnicodeDigits() {
    assertThat(PanMasker.mask("4111.1111.1111.1111")).doesNotContain("4111.1111");
    assertThat(PanMasker.mask("4111  1111  1111  1111")).doesNotContain("4111  1111");
    assertThat(PanMasker.mask("4111/1111/1111/1111")).doesNotContain("4111/1111");
    assertThat(PanMasker.mask("\uFF14\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11")).doesNotContain("\uFF14\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11\uFF11");
  }

  @Test
  @DisplayName("Leaves ordinary text and null alone")
  void leavesOrdinaryTextAndNullAlone() {
    assertThat(PanMasker.mask("posting 7 ok")).isEqualTo("posting 7 ok");
    assertThat(PanMasker.mask(null)).isNull();
  }

  /** Fails if digits are read as chars: a digit outside the Basic Multilingual Plane is two chars, and the number went through unmasked. */
  @Test
  @DisplayName("Finds a card number written in digits outside the Basic Multilingual Plane")
  void findsANumberWrittenInDigitsOutsideTheBasicMultilingualPlane() {
    StringBuilder bold = new StringBuilder();
    "4111111111111111".chars().forEach(c -> bold.appendCodePoint(0x1D7CE + (c - '0'))); // MATHEMATICAL BOLD DIGIT ZERO onwards
    String masked = PanMasker.mask("card " + bold);
    assertThat(masked.codePoints().filter(Character::isDigit).count()).as("only the last four stay").isEqualTo(4);
  }
}
