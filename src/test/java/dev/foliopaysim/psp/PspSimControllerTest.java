package dev.foliopaysim.psp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PspSimControllerTest {
  @Test
  void aCardNumberNeverAppearsWhenTheAuthorisationRequestIsPrinted() {
    var request = new PspSimController.AuthRequest("4111111111111111", "12/29", 100, "USD");
    assertThat(request.toString()).isEqualTo("AuthRequest[redacted]").doesNotContain("4111").doesNotContain("12/29");
  }
}
