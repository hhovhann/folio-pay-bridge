package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.foliopay.AuditCapture;
import dev.foliopay.gateway.fake.FakePsp;
import dev.foliopay.logging.AuditLog.Event;
import dev.foliopay.service.support.Fingerprint;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The audit trail end to end (PCI DSS v4.0.1 10.2, D-43): through the real security filters, controllers, vault and charge service, with
 * API keys enforced. Each test reads the lines the audit logger wrote, before any masking, so a secret that reached one would show here.
 */
@SpringBootTest(properties = "foliopay.demo-controls=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Audit trail: who did what to which resource, when, and with what outcome")
class AuditTrailIntegrationTest {
  private static final String PMS_KEY = "fpb_test_pms0000000000000000000000000000";
  private static final String READ_KEY = "fpb_test_read000000000000000000000000000";
  private static final String PAN = "4111111111111111";
  @Autowired private MockMvc mvc;
  @Autowired private FakePsp psp;
  private AuditCapture audit;

  @DynamicPropertySource
  static void apiKeys(DynamicPropertyRegistry registry) {
    registry.add("foliopay.auth.api-keys", () -> "pms-1:" + HexFormat.of().formatHex(Fingerprint.sha256(PMS_KEY)) + ":cards:write payments:write,"
        + "finance:" + HexFormat.of().formatHex(Fingerprint.sha256(READ_KEY)) + ":payments:read");
  }

  @BeforeEach
  void listen() {
    psp.reset();
    audit = new AuditCapture();
  }

  @AfterEach
  void stopListening() {
    audit.close();
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String key, String body) {
    return request.header("X-Api-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private String tokenise() throws Exception {
    String body = mvc.perform(json(post("/api/v1/cards"), PMS_KEY, "{\"pan\":\"" + PAN + "\",\"expiry\":\"12/29\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return new ObjectMapper().readTree(body).path("token").asText();
  }

  private void pay(String token, String key) throws Exception {
    mvc.perform(json(post("/api/v1/payments"), PMS_KEY, "{\"idempotencyKey\":\"" + key + "\",\"bookingRef\":\"BK-1\",\"folioId\":\"F-1\","
        + "\"token\":\"" + token + "\",\"amountMinor\":5000,\"currency\":\"USD\"}"));
  }

  @Test
  @DisplayName("An unknown API key is recorded as a failed authentication, with the path, method and origin, and never the key")
  void anUnknownApiKeyIsRecordedAsAFailedAuthentication() throws Exception {
    String unknown = "fpb_test_" + "unknown".repeat(5);
    mvc.perform(json(post("/api/v1/payments"), unknown, "{}")).andExpect(status().isUnauthorized());

    assertThat(audit.of(Event.AUTHENTICATION_FAILED)).singleElement().asString()
        .contains("outcome=FAILURE", "client=unauthenticated", "resource=/api/v1/payments", "method=POST", "origin=127.0.0.1")
        .doesNotContain(unknown);
  }

  @Test
  @DisplayName("A client refused for its scope is recorded as access denied, under its own name")
  void aClientRefusedForItsScopeIsRecordedAsAccessDenied() throws Exception {
    mvc.perform(json(post("/api/v1/payments"), READ_KEY, "{}")).andExpect(status().isForbidden());

    assertThat(audit.of(Event.ACCESS_DENIED)).singleElement().asString()
        .contains("outcome=FAILURE", "client=finance", "resource=/api/v1/payments", "method=POST");
  }

  @Test
  @DisplayName("A payment leaves its card stored, its card opened, its capture and both calls in the trail, with no card token or number")
  void aPaymentLeavesItsWholeTrailWithNoCardTokenOrNumber() throws Exception {
    String token = tokenise();
    pay(token, "audit-1");

    String card = "resource=" + Fingerprint.card(token);
    assertThat(audit.of(Event.CARD_STORED)).singleElement().asString().contains("client=pms-1", card, "outcome=SUCCESS");
    assertThat(audit.of(Event.CARD_OPENED)).singleElement().asString().contains("client=pms-1", card, "outcome=SUCCESS");
    assertThat(audit.of(Event.PAYMENT_CAPTURED)).singleElement().asString().contains("client=pms-1", "key=audit-1", "amount=5000", "currency=USD");
    assertThat(audit.of(Event.API_CALL)).extracting(line -> line.replaceAll(".* resource=(\\S+) .*", "$1"))
        .containsExactly("/api/v1/cards", "/api/v1/payments");
    assertThat(audit.lines()).isNotEmpty().allSatisfy(line -> assertThat(line).doesNotContain(token).doesNotContain(PAN));
  }

  @Test
  @DisplayName("A declined payment is recorded as a failure with the processor's reason")
  void aDeclinedPaymentIsRecordedAsAFailure() throws Exception {
    String token = tokenise();
    psp.arm(FakePsp.Fault.DECLINE_NEXT);
    pay(token, "audit-2");

    assertThat(audit.of(Event.PAYMENT_DECLINED)).singleElement().asString().contains("outcome=FAILURE", "key=audit-2", "reason=");
    assertThat(audit.of(Event.PAYMENT_CAPTURED)).isEmpty();
  }

  @Test
  @DisplayName("Reads are not recorded as calls: only what changes state is")
  void readsAreNotRecordedAsCalls() throws Exception {
    mvc.perform(get("/api/v1/charges").header("X-Api-Key", READ_KEY)).andExpect(status().isOk());

    assertThat(audit.of(Event.API_CALL)).isEmpty();
  }

  @Test
  @DisplayName("A refused call that reached its controller is recorded as a failed call with its status")
  void aRefusedCallThatReachedItsControllerIsRecordedAsAFailedCall() throws Exception {
    mvc.perform(json(post("/api/v1/payments"), PMS_KEY, "{\"idempotencyKey\":\"bad\"}")).andExpect(status().isBadRequest());

    List<String> calls = audit.of(Event.API_CALL);
    assertThat(calls).singleElement().asString().contains("outcome=FAILURE", "status=400", "client=pms-1");
  }
}
