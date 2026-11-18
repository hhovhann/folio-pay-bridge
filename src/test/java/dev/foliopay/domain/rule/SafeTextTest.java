package dev.foliopay.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeTextTest {
  @Test
  void flattensLineBreaksAndControlCharacters() {
    assertThat(SafeText.clean("a\nb\r\nc\td\u0000e f")).startsWith("a b c d e f").doesNotContain("\n").doesNotContain("\r").doesNotContain("\u0000").doesNotContain(" ");
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
