package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.foliopay.TestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP end to end: JSON in, JSON out, real services and database behind it. */
@AutoConfigureMockMvc
@DisplayName("API end to end over HTTP")
class ApiIntegrationTest extends TestBase {
  @Autowired MockMvc mvc;

  private String json(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
    return actions.andReturn().getResponse().getContentAsString();
  }

  @Test
  @DisplayName("Tokenising returns a token and never the number, nor does the vault view")
  void tokenisingReturnsATokenAndNeverTheNumberNorDoesTheVaultView() throws Exception {
    String body = json(mvc.perform(post("/api/v1/cards").contentType(MediaType.APPLICATION_JSON).content("{\"pan\":\"4242 4242 4242 4242\",\"expiry\":\"11/28\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.startsWith("tok_"))).andExpect(jsonPath("$.last4").value("4242")));
    assertThat(body).doesNotContain("4242424242424242").doesNotContain("4242 4242");
    assertThat(json(mvc.perform(get("/api/v1/vault")))).doesNotContain("4242424242424242");
  }

  @Test
  @DisplayName("A bad card number is rejected without echoing it")
  void aBadCardNumberIsRejectedWithoutEchoingIt() throws Exception {
    String body = json(mvc.perform(post("/api/v1/cards").contentType(MediaType.APPLICATION_JSON).content("{\"pan\":\"4111111111111112\",\"expiry\":\"11/28\"}")).andExpect(status().isBadRequest()));
    assertThat(body).doesNotContain("4111111111111112");
  }

  @Test
  @DisplayName("A payment repeated over HTTP is one capture; a different amount under the same key is 409")
  void aPaymentRepeatedOverHttpIsOneCaptureAndADifferentAmountUnderTheSameKeyIs409() throws Exception {
    String request = "{\"idempotencyKey\":\"http-1\",\"bookingRef\":\"BK-1\",\"folioId\":\"F-1\",\"token\":\"" + token + "\",\"amountMinor\":12345,\"currency\":\"USD\"}";
    for (int i = 0; i < 2; i++) {
      mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("OK"));
    }
    assertThat(psp.captureCount()).isEqualTo(1);
    mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(request.replace("12345", "999"))).andExpect(status().isConflict());
    assertThat(psp.capturedTotalMinor()).isEqualTo(12_345);
  }

  @Test
  @DisplayName("The charge list never exposes the card token")
  void theChargeListNeverExposesTheCardToken() throws Exception {
    mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON)
        .content("{\"idempotencyKey\":\"http-2\",\"bookingRef\":\"BK-2\",\"folioId\":\"F-1\",\"token\":\"" + token + "\",\"amountMinor\":500,\"currency\":\"USD\"}"));
    assertThat(json(mvc.perform(get("/api/v1/charges")))).contains("http-2").doesNotContain(token);
  }
}
