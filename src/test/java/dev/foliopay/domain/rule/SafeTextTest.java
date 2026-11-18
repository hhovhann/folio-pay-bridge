package dev.foliopay.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeTextTest {
  @Test
  void flattensLineBreaksAndControlCharacters() {
    assertThat(SafeText.clean("a\nb\r\nc\td\u0000e f")).startsWith("a b c d e f").doesNotContain("\n").doesNotContain("\r").doesNotContain("\u0000").doesNotContain(" ");
  }

  /**
   * Fails if invisible characters survive: a right-to-left override shows "Refund 001 USD" as something else on a dashboard, and Unicode
   * tag characters carry text a person cannot see but a language model reading the record would (prompt injection).
   */
  @Test
  void removesInvisibleFormattingCharactersThatShowAPersonOneThingAndAProgramAnother() {
    String hidden = new String(Character.toChars(0xE0049)) + new String(Character.toChars(0xE0047)); // tag letters "I", "G"
    String cleaned = SafeText.clean("Spa ‮parcel‬ treatment​" + hidden + " for room 12﻿");

    assertThat(cleaned).isEqualTo("Spa parcel treatment for room 12");
    assertThat(cleaned.codePoints().filter(c -> Character.getType(c) == Character.FORMAT)).isEmpty();
  }

  @Test
  void boundsTheLengthAndMarksTheCut() {
    assertThat(SafeText.clean("x".repeat(1_000))).hasSize(255).endsWith("...");
  }

  @Test
  void leavesNullAndShortTextAlone() {
    assertThat(SafeText.clean(null)).isNull();
    assertThat(SafeText.clean("Folio is closed")).isEqualTo("Folio is closed");
  }
}
