package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.foliopay.TestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Every endpoint, over HTTP, against the whole application: success, refusal and the shapes of the answers. */
@AutoConfigureMockMvc
class ApiEndpointsIntegrationTest extends TestBase {
  private static final ObjectMapper JSON = new ObjectMapper();
  @Autowired MockMvc mvc;

  private ResultActions send(String method, String path, String body) throws Exception {
    var request = method.equals("GET") ? get(path) : post(path);
    return mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private JsonNode body(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private String event(String type, String ref, String plan, long total) {
    return "{\"type\":\"" + type + "\",\"bookingRef\":\"" + ref + "\",\"folioId\":\"F-" + ref + "\",\"ratePlan\":\"" + plan
        + "\",\"arrival\":\"2026-12-20\",\"totalMinor\":" + total + ",\"currency\":\"USD\",\"today\":\"2026-11-17\"}";
  }

  private String newLinkId(String ref, String plan, long total) throws Exception {
    JsonNode outcome = body(send("POST", "/api/v1/booking-events", event("NEW", ref, plan, total)).andExpect(status().isOk()));
    return outcome.path("links").get(0).path("id").asText();
  }

  @Test
  void aBookingEventBecomesAPaymentLinkThatAGuestPaysAndTheLedgerShowsIt() throws Exception {
    String id = newLinkId("B-EP1", "ADVANCE_PURCHASE", 80_000);

    send("GET", "/api/v1/links", null).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("OPEN")).andExpect(jsonPath("$[0].amountMinor").value(80_000));
    JsonNode paid = body(send("POST", "/api/v1/links/" + id + "/pay", "{\"token\":\"" + token + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("OK")));
    send("GET", "/api/v1/links", null).andExpect(jsonPath("$[0].status").value("PAID"));
    send("GET", "/api/v1/charges", null).andExpect(jsonPath("$[0].status").value("CAPTURED"));
    send("GET", "/api/v1/postings", null).andExpect(jsonPath("$[0].status").value("POSTED"));
    send("POST", "/api/v1/postings/" + paid.path("posting").path("id").asText() + "/replay", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("POSTED"));
  }

  @Test
  void payingAnUnknownOrDeadLinkIsRefusedWithTheRightStatus() throws Exception {
    send("POST", "/api/v1/links/lnk_nope/pay", "{\"token\":\"" + token + "\"}").andExpect(status().isNotFound());
    String id = newLinkId("B-EP2", "FLEXIBLE", 100_000);
    send("POST", "/api/v1/booking-events", event("CANCELLED", "B-EP2", "FLEXIBLE", 100_000)).andExpect(status().isOk());
    send("POST", "/api/v1/links/" + id + "/pay", "{\"token\":\"" + token + "\"}").andExpect(status().isGone());
    send("POST", "/api/v1/postings/nope/replay", null).andExpect(status().isNotFound());
  }

  @Test
  void aBadBookingEventIsRefusedBeforeItChangesAnything() throws Exception {
    send("POST", "/api/v1/booking-events", event("NEW", "bad ref", "FLEXIBLE", 1_000)).andExpect(status().isBadRequest());
    send("POST", "/api/v1/booking-events", event("EXPLODE", "B-X", "FLEXIBLE", 1_000)).andExpect(status().isBadRequest());
    send("GET", "/api/v1/links", null).andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void reconciliationAcceptsAPastedStatementAndReportsEachProblem() throws Exception {
    send("POST", "/api/v1/reconcile", "{\"csv\":\"psp_ref,amount_minor,currency\\ncap_ghost,700,USD\"}").andExpect(status().isOk())
        .andExpect(jsonPath("$.exceptions.UNKNOWN_IN_SETTLEMENT").value(1));
    send("POST", "/api/v1/reconcile", "{\"csv\":\"ref,amount\\ncap_1,100\"}").andExpect(status().isBadRequest());
    send("POST", "/api/v1/reconcile", "{\"mode\":\"sideways\"}").andExpect(status().isBadRequest());
  }

  @Test
  void reconciliationAgainstTheAcquirerStatementCleanAndMessy() throws Exception {
    for (int i = 0; i < 3; i++) {
      String id = newLinkId("B-R" + i, "ADVANCE_PURCHASE", 10_000L * (i + 1));
      send("POST", "/api/v1/links/" + id + "/pay", "{\"token\":\"" + token + "\"}").andExpect(status().isOk());
    }
    send("POST", "/api/v1/reconcile", "{\"mode\":\"clean\"}").andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(3)).andExpect(jsonPath("$.exceptions").isEmpty());
    JsonNode messy = body(send("POST", "/api/v1/reconcile", "{\"mode\":\"messy\"}").andExpect(status().isOk()));
    assertThat(messy.path("exceptions").fieldNames()).toIterable().contains("MISSING_IN_SETTLEMENT", "AMOUNT_DIFFERENCE", "UNKNOWN_IN_SETTLEMENT");
  }

  @Test
  void shadowModeRunsOnlyWithValidInputAndReportsTheComparison() throws Exception {
    send("POST", "/api/v1/shadow", "{\"bookings\":4,\"seed\":1,\"mode\":\"compat\"}").andExpect(status().isOk()).andExpect(jsonPath("$.bookings").value(4)).andExpect(jsonPath("$.mismatched").value(0));
    send("POST", "/api/v1/shadow", "{\"bookings\":3,\"mode\":\"strict\"}").andExpect(status().isOk());
    send("POST", "/api/v1/shadow", "{\"bookings\":9999}").andExpect(status().isBadRequest());
    send("POST", "/api/v1/shadow", "{\"mode\":\"nope\"}").andExpect(status().isBadRequest());
  }

  @Test
  void theDemoControlsShowStateArmFaultsAndResetAndAnUnknownFaultIsRefused() throws Exception {
    send("GET", "/api/v1/sim", null).andExpect(status().isOk()).andExpect(jsonPath("$.opera").value("NONE"));
    send("POST", "/api/v1/sim", "{\"psp\":\"DECLINE_NEXT\",\"opera\":\"DOWN\"}").andExpect(status().isOk()).andExpect(jsonPath("$.psp").value("DECLINE_NEXT")).andExpect(jsonPath("$.opera").value("DOWN"));
    send("POST", "/api/v1/sim", "{\"opera\":\"NOT_A_FAULT\"}").andExpect(status().isBadRequest());
    send("POST", "/api/v1/sim", "{\"reset\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.psp").value("NONE")).andExpect(jsonPath("$.pspCaptures").value(0));
    send("GET", "/api/v1/vault", null).andExpect(status().isOk()).andExpect(jsonPath("$[0].sealedPanHex").exists());
    send("GET", "/api/v1/logs", null).andExpect(status().isOk());
  }

  @Test
  void theFrameworksOwnErrorsAreTurnedIntoOneSafeShape() throws Exception {
    send("GET", "/api/v1/cards", null).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.detail").exists());
    send("GET", "/api/v1/nothing-here", null).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").exists());
    mvc.perform(post("/api/v1/payments").contentType(MediaType.TEXT_PLAIN).content("hello")).andExpect(status().isUnsupportedMediaType());
    send("POST", "/api/v1/payments", "{not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Bad Request"));
  }

  /** Fails if an error leaves the standard shape: a client parses RFC 9457 problem details and acts on the stable code, not the text. */
  @Test
  void errorsAreProblemDetailsWithAStableCode() throws Exception {
    send("GET", "/api/v1/nothing-here", null)
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.title").value("Not Found")).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    send("POST", "/api/v1/postings/nope/replay", null)
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("Unknown posting")).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get("/api/v1/charges").header("Host", "evil.example.test")) // refused by a filter, before Spring MVC: the same shape
        .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }
}
