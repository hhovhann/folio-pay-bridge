package dev.foliopaysim.psp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Processor simulator: never prints a card number")
class PspSimControllerTest {
  @Test
  @DisplayName("A card number never appears when the authorisation request is printed")
  void aCardNumberNeverAppearsWhenTheAuthorisationRequestIsPrinted() {
    var request = new PspSimController.AuthRequest("4111111111111111", "12/29", 100, "USD");
    assertThat(request.toString()).isEqualTo("AuthRequest[redacted]").doesNotContain("4111").doesNotContain("12/29");
  }
}
