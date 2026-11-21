package dev.foliopaysim.psp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The processor simulator's stand-in for the card network: only the first digits decide, so a prefix is enough to name one. */
@DisplayName("Processor simulator: the card network behind the processor")
class PspSimulatorTest {
  /** Fails if a card is routed to the wrong network, or if a number that matches none, or no number at all, is given a real one. */
  @Test
  @DisplayName("The card network is named from the card's first digits, as an acquirer routes it")
  void theCardNetworkIsNamedFromTheCardsFirstDigits() {
    assertThat(PspSimulator.network("4000")).isEqualTo("VISA");
    assertThat(PspSimulator.network("5100")).isEqualTo("MASTERCARD");
    assertThat(PspSimulator.network("2221")).as("Mastercard's 2-series").isEqualTo("MASTERCARD");
    assertThat(PspSimulator.network("3400")).isEqualTo("AMEX");
    assertThat(PspSimulator.network("3700")).isEqualTo("AMEX");
    assertThat(PspSimulator.network("3000")).as("a 3 that is not 34 or 37").isEqualTo("OTHER");
    assertThat(PspSimulator.network("6011")).isEqualTo("OTHER");
    assertThat(PspSimulator.network("")).isEqualTo("OTHER");
    assertThat(PspSimulator.network(null)).isEqualTo("OTHER");
  }
}
