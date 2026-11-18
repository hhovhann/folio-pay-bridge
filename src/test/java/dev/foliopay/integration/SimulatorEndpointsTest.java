package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.foliopay.integration.SimulatorHarness.Reply;
import dev.foliopay.integration.SimulatorHarness.Sim;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Every endpoint of both simulators, including the ways each refuses a bad call, so the demo's outside world is itself tested. */
class SimulatorEndpointsTest {
  private static final RestClient HTTP = RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory()).build(); // HTTP/1.1, like the real adapters: the JDK client may retry a cleartext POST over h2c
  private static final HttpClient RAW = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private static final String TODAY = LocalDate.now(ZoneId.of("Asia/Dubai")).toString();
  private static Sim opera;
  private static Sim psp;

  @BeforeAll
  static void start() {
    opera = SimulatorHarness.start("opera");
    psp = SimulatorHarness.start("psp");
  }

  @AfterAll
  static void stop() {
    opera.close();
    psp.close();
  }

  @BeforeEach
  void reset() {
    HTTP.post().uri(opera.url() + "/sim/reset").retrieve().toBodilessEntity();
    HTTP.post().uri(psp.url() + "/sim/reset").retrieve().toBodilessEntity();
  }

  private static int statusOf(RestClient.RequestHeadersSpec<?> request) {
    return request.exchange((req, res) -> res.getStatusCode().value());
  }

  private static JsonNode json(RestClient.RequestHeadersSpec<?> request) {
    return request.exchange((req, res) -> res.bodyTo(JsonNode.class));
  }

  private static final String BASIC = "Basic " + java.util.Base64.getEncoder().encodeToString("fpbridge:fpbridge-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));

  private static RestClient.RequestBodySpec tokenRequest(String appKey, String enterprise, String basic) {
    RestClient.RequestBodySpec spec = HTTP.post().uri(opera.url() + "/oauth/v1/tokens").contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
    if (appKey != null) spec.header("x-app-key", appKey);
    if (enterprise != null) spec.header("enterpriseId", enterprise);
    if (basic != null) spec.header("Authorization", basic);
    return spec;
  }

  private static String token() {
    return tokenRequest("demo-app-key", "DEMOENT", BASIC).body("grant_type=client_credentials&scope=demo-scope").retrieve().body(JsonNode.class).path("access_token").asText();
  }

  private static RestClient.RequestBodySpec operaPost(String path, String bearer) {
    return HTTP.post().uri(opera.url() + path).header("Authorization", "Bearer " + bearer).header("x-app-key", "demo-app-key").header("x-hotelid", "DEMO1").contentType(org.springframework.http.MediaType.APPLICATION_JSON);
  }

  /** A postBillingPayment body, as Oracle's billingPayment definition shapes it. */
  private static Map<String, Object> payment(String reservation, String method, long cashier, Object amount) {
    return Map.of("criteria", Map.of("hotelId", "DEMO1", "reservationId", Map.of("id", reservation, "type", "Reservation"), "folioWindowNo", 1, "cashierId", cashier,
        "paymentMethod", Map.of("paymentMethod", method), "postingAmount", Map.of("amount", amount, "currencyCode", "USD"), "postingReference", "r1",
        "postingRemark", "Processor reference cap_1", "action", "Billing"));
  }

  private static JsonNode frontDesk(String path) { return json(HTTP.post().uri(opera.url() + path)); }

  private static int frontDeskStatus(String path) { return statusOf(HTTP.post().uri(opera.url() + path)); }

  private static int redirectStatus(String url) throws Exception {
    return RAW.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  // ---- OPERA: the OHIP surface
  @Test
  void theFrontDeskHomeRedirectsToItsPage() throws Exception {
    assertThat(redirectStatus(opera.url() + "/")).isEqualTo(302);
  }

  @Test
  void theTokenEndpointWantsBasicCredentialsTheAppKeyTheEnterpriseAndAScopeAndRefusesWhenDown() {
    String form = "grant_type=client_credentials&scope=demo-scope";
    assertThat(statusOf(tokenRequest("wrong", "DEMOENT", BASIC).body(form))).as("wrong app key").isEqualTo(401);
    assertThat(statusOf(tokenRequest("demo-app-key", "OTHER", BASIC).body(form))).as("wrong enterprise").isEqualTo(401);
    assertThat(statusOf(tokenRequest("demo-app-key", "DEMOENT", null).body(form))).as("no client credentials").isEqualTo(401);
    assertThat(statusOf(tokenRequest("demo-app-key", "DEMOENT", "Basic %%%").body(form))).as("unreadable credentials").isEqualTo(401);
    assertThat(statusOf(tokenRequest("demo-app-key", "DEMOENT", BASIC).body("grant_type=client_credentials"))).as("no scope").isEqualTo(400);
    assertThat(statusOf(tokenRequest("demo-app-key", "DEMOENT", BASIC).body("grant_type=password&scope=s"))).as("not client credentials").isEqualTo(400);
    assertThat(json(tokenRequest("demo-app-key", "DEMOENT", BASIC).body(form)).path("token_type").asText()).isEqualTo("Bearer");
    HTTP.post().uri(opera.url() + "/sim/faults").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("fault", "DOWN")).retrieve().toBodilessEntity();
    assertThat(statusOf(tokenRequest("demo-app-key", "DEMOENT", BASIC).body(form))).isEqualTo(503);
  }

  @Test
  void everyResourceCallNeedsAValidTokenTheRightAppKeyAndTheMatchingHotelHeader() {
    String bearer = token();
    String url = opera.url() + "/bof/v1/hotels/DEMO1/businessDate";
    assertThat(statusOf(HTTP.get().uri(url).header("x-app-key", "demo-app-key").header("x-hotelid", "DEMO1"))).as("no token").isEqualTo(401);
    assertThat(statusOf(HTTP.get().uri(url).header("Authorization", "Bearer " + bearer).header("x-hotelid", "DEMO1"))).as("no app key").isEqualTo(401);
    assertThat(statusOf(HTTP.get().uri(url).header("Authorization", "Bearer " + bearer).header("x-app-key", "demo-app-key").header("x-hotelid", "OTHER"))).as("wrong hotel").isEqualTo(400);
    JsonNode reply = json(HTTP.get().uri(url).header("Authorization", "Bearer " + bearer).header("x-app-key", "demo-app-key").header("x-hotelid", "DEMO1"));
    assertThat(reply.path("hotels").get(0).path("hotelId").asText()).isEqualTo("DEMO1");
    assertThat(reply.path("hotels").get(0).path("businessDate").asText()).isEqualTo(TODAY);
  }

  @Test
  void aPaymentIsRefusedForEachReasonARealPmsWouldGiveWithOperasErrorCode() {
    String bearer = token();
    String path = "/csh/v1/hotels/DEMO1/reservations/";
    JsonNode missing = json(operaPost(path + "GHOST-1/payments", bearer).body(payment("GHOST-1", "CC", 9001, 10.00)));
    assertThat(missing.path("status").asInt()).as("no such reservation").isEqualTo(404);
    assertThat(missing.path("o:errorCode").asText()).isEqualTo("RESERVATION_NOT_FOUND");
    assertThat(statusOf(operaPost(path + "CLOSED-9/payments", bearer).body(payment("CLOSED-9", "CC", 9001, 10.00)))).as("checked out").isEqualTo(400);
    assertThat(statusOf(operaPost(path + "RES-1/payments", bearer).body(payment("RES-1", "ZZ", 9001, 10.00)))).as("unknown payment method").isEqualTo(400);
    assertThat(statusOf(operaPost(path + "RES-1/payments", bearer).body(payment("RES-1", "CC", 4242, 10.00)))).as("unknown cashier").isEqualTo(400);
    assertThat(statusOf(operaPost(path + "RES-1/payments", bearer).body(payment("RES-1", "CC", 9001, 10.001)))).as("a fraction of a cent").isEqualTo(400);
    assertThat(statusOf(operaPost(path + "RES-1/payments", bearer).body(payment("RES-1", "CC", 9001, -5)))).as("not positive").isEqualTo(400);
    assertThat(statusOf(operaPost(path + "RES-1/payments", bearer).body(payment("RES-2", "CC", 9001, 10.00)))).as("body names another reservation").isEqualTo(400);
    assertThat(statusOf(operaPost(path + "RES-1/payments", bearer).body(Map.of("nothing", 1)))).as("no criteria").isEqualTo(400);
    assertThat(statusOf(operaPost(path + "RES-1/payments", bearer).body(payment("RES-1", "CC", 9001, 10.00)))).as("a good one").isEqualTo(201);
  }

  @Test
  void aThrottleAndALockConflictEachHitOnlyTheNextPaymentThenClear() {
    String bearer = token();
    String path = "/csh/v1/hotels/DEMO1/reservations/RES-1/payments";
    HTTP.post().uri(opera.url() + "/sim/faults").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("fault", "THROTTLE_NEXT")).retrieve().toBodilessEntity();
    assertThat(statusOf(operaPost(path, bearer).body(payment("RES-1", "CC", 9001, 10.00)))).isEqualTo(429);
    assertThat(statusOf(operaPost(path, bearer).body(payment("RES-1", "CC", 9001, 10.00)))).isEqualTo(201);
    HTTP.post().uri(opera.url() + "/sim/faults").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("fault", "LOCK_CONFLICT_NEXT")).retrieve().toBodilessEntity();
    assertThat(statusOf(operaPost(path, bearer).body(payment("RES-1", "CC", 9001, 10.00)))).isEqualTo(409);
    assertThat(statusOf(operaPost(path, bearer).body(payment("RES-1", "CC", 9001, 10.00)))).isEqualTo(201);
  }

  @Test
  void nightAuditMovesTheBusinessDateAndExpiringTokensForcesANewOne() {
    HTTP.post().uri(opera.url() + "/sim/night-audit").retrieve().toBodilessEntity();
    assertThat(json(HTTP.get().uri(opera.url() + "/sim/state")).path("businessDate").asText()).isEqualTo(LocalDate.parse(TODAY).plusDays(1).toString());
    String bearer = token();
    HTTP.post().uri(opera.url() + "/sim/expire-tokens").retrieve().toBodilessEntity();
    assertThat(statusOf(HTTP.get().uri(opera.url() + "/bof/v1/hotels/DEMO1/businessDate").header("Authorization", "Bearer " + bearer).header("x-app-key", "demo-app-key").header("x-hotelid", "DEMO1"))).isEqualTo(401);
  }

  // ---- OPERA: the front desk
  /** A guest's stay as a front desk sees it: check in, a room night, a payment that settles it, then check-out, which needs a zero balance. */
  @Test
  void aGuestChecksInIsChargedPaysAndCanCheckOutOnlyWhenTheFolioIsSettled() {
    assertThat(frontDesk("/sim/reservations/RES-1002/check-in").toString()).contains("IN_HOUSE");
    assertThat(frontDeskStatus("/sim/reservations/RES-1002/check-in")).as("already in house").isEqualTo(400);
    frontDesk("/sim/reservations/RES-1002/room-charge");
    JsonNode folio = json(HTTP.get().uri(opera.url() + "/sim/reservations/RES-1002/folio"));
    assertThat(folio.path("balance").asText()).isEqualTo("320.00");
    assertThat(frontDeskStatus("/sim/reservations/RES-1002/check-out")).as("a balance is owed").isEqualTo(400);

    operaPost("/csh/v1/hotels/DEMO1/reservations/RES-1002/payments", token()).body(payment("RES-1002", "CC", 9001, 320.00)).retrieve().toBodilessEntity();
    assertThat(json(HTTP.get().uri(opera.url() + "/sim/reservations/RES-1002/folio")).path("balance").asText()).isEqualTo("0.00");
    assertThat(frontDesk("/sim/reservations/RES-1002/check-out").toString()).contains("CHECKED_OUT");
    assertThat(statusOf(operaPost("/csh/v1/hotels/DEMO1/reservations/RES-1002/payments", token()).body(payment("RES-1002", "CC", 9001, 1.00))))
        .as("a checked-out folio is closed").isEqualTo(400);
    assertThat(frontDeskStatus("/sim/reservations/RES-1002/room-charge")).as("no room nights after departure").isEqualTo(400);
    assertThat(frontDeskStatus("/sim/reservations/RES-1002/check-out")).isEqualTo(400);
    assertThat(statusOf(HTTP.get().uri(opera.url() + "/sim/reservations/GHOST-2/folio"))).isEqualTo(404);
  }

  @Test
  void nightAuditPostsARoomNightToEveryGuestInHouseOnTheDayItCloses() {
    int before = json(HTTP.get().uri(opera.url() + "/sim/reservations/RES-1001/folio")).path("postings").size();
    HTTP.post().uri(opera.url() + "/sim/night-audit").retrieve().toBodilessEntity();
    JsonNode postings = json(HTTP.get().uri(opera.url() + "/sim/reservations/RES-1001/folio")).path("postings");
    assertThat(postings).hasSize(before + 1);
    assertThat(postings.get(before).path("businessDate").asText()).isEqualTo(TODAY);
    assertThat(json(HTTP.get().uri(opera.url() + "/sim/reservations/RES-1002/folio")).path("postings")).as("not checked in: nothing posted").isEmpty();
  }

  @Test
  void thePmsCanPushABookingEventToTheBridgeAndSaysSoWhenItCannot() {
    assertThat(json(HTTP.post().uri(opera.url() + "/sim/booking-events").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(Map.of("bookingRef", "B1", "ratePlan", "FLEXIBLE", "arrivalInDays", 30, "totalMinor", 100000))).path("error").asText()).contains("No bridge configured");

    try (var bridge = SimulatorHarness.stub((m, path) -> new Reply(200, "{\"note\":\"Flexible: 20% deposit\"}"));
         Sim wired = SimulatorHarness.start("opera", "--sim.opera.bridge-url=" + bridgeUrl(bridge))) {
      assertThat(json(HTTP.post().uri(wired.url() + "/sim/booking-events").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .body(Map.of("bookingRef", "B1", "ratePlan", "FLEXIBLE", "arrivalInDays", 30, "totalMinor", 100000))).path("note").asText()).contains("Flexible: 20% deposit");
    }
    try (var broken = SimulatorHarness.stub((m, path) -> new Reply(500, "{}"));
         Sim wired = SimulatorHarness.start("opera", "--sim.opera.bridge-url=" + bridgeUrl(broken))) {
      assertThat(json(HTTP.post().uri(wired.url() + "/sim/booking-events").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .body(Map.of("bookingRef", "B1", "ratePlan", "FLEXIBLE", "arrivalInDays", 30, "totalMinor", 100000))).path("error").asText()).contains("did not accept");
    }
  }

  private static String bridgeUrl(SimulatorHarness.Stub stub) { return stub.url(); }

  // ---- processor
  private static RestClient.RequestBodySpec pspPost(String path, String key, String idempotencyKey) {
    RestClient.RequestBodySpec spec = HTTP.post().uri(psp.url() + path).contentType(org.springframework.http.MediaType.APPLICATION_JSON);
    if (key != null) spec.header("Authorization", "Bearer " + key);
    if (idempotencyKey != null) spec.header("Idempotency-Key", idempotencyKey);
    return spec;
  }

  @Test
  void theProcessorHomeRedirectsAndEveryMoneyCallNeedsAnApiKeyAndAnIdempotencyKey() throws Exception {
    assertThat(redirectStatus(psp.url() + "/")).isEqualTo(302);
    Map<String, Object> auth = Map.of("pan", "4111111111111111", "expiry", "12/29", "amountMinor", 100, "currency", "USD");
    assertThat(statusOf(pspPost("/v1/authorizations", null, "k").body(auth))).as("no api key").isEqualTo(401);
    assertThat(statusOf(pspPost("/v1/authorizations", "demo-psp-key", null).body(auth))).as("no idempotency key").isEqualTo(400);
    assertThat(statusOf(pspPost("/v1/captures", "wrong", "k").body(Map.of("authRef", "a", "amountMinor", 1, "currency", "USD")))).isEqualTo(401);
    assertThat(statusOf(pspPost("/v1/voids", "wrong", "k").body(Map.of("authRef", "a")))).isEqualTo(401);
    assertThat(statusOf(HTTP.get().uri(psp.url() + "/v1/settlements"))).isEqualTo(401);
  }

  @Test
  void aRepeatedKeyReturnsTheFirstResultAndTheLedgerKeepsOnlyTheLastFourDigits() {
    Map<String, Object> auth = Map.of("pan", "4111111111111111", "expiry", "12/29", "amountMinor", 100, "currency", "USD");
    String first = json(pspPost("/v1/authorizations", "demo-psp-key", "same").body(auth)).path("ref").asText();
    String second = json(pspPost("/v1/authorizations", "demo-psp-key", "same").body(auth)).path("ref").asText();
    assertThat(second).isEqualTo(first);
    JsonNode ledger = json(HTTP.get().uri(psp.url() + "/sim/state")).path("ledger");
    assertThat(ledger).hasSize(1);
    assertThat(ledger.toString()).contains("1111").doesNotContain("4111111111111111");
  }

  @Test
  void aCaptureDeclineAndALostCaptureReplyEachHitOnlyTheNextCall() {
    String authRef = authorise("a1", 100);
    HTTP.post().uri(psp.url() + "/sim/faults").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("fault", "DECLINE_CAPTURE_NEXT")).retrieve().toBodilessEntity();
    Map<String, Object> capture = Map.of("authRef", authRef, "amountMinor", 100, "currency", "USD");
    assertThat(statusOf(pspPost("/v1/captures", "demo-psp-key", "c1").body(capture))).isEqualTo(402);
    assertThat(statusOf(pspPost("/v1/captures", "demo-psp-key", "c1").body(capture))).isEqualTo(201);
    assertThat(json(HTTP.get().uri(psp.url() + "/v1/settlements").header("Authorization", "Bearer demo-psp-key")).path("lines")).hasSize(1);
  }

  @Test
  void aCaptureOrVoidMustNameARealAuthorisationAndACaptureMustMatchItsAmount() {
    String authRef = authorise("a2", 100);
    assertThat(statusOf(pspPost("/v1/captures", "demo-psp-key", "c2").body(Map.of("authRef", "auth_nope", "amountMinor", 100, "currency", "USD")))).isEqualTo(404);
    assertThat(statusOf(pspPost("/v1/captures", "demo-psp-key", "c3").body(Map.of("authRef", authRef, "amountMinor", 101, "currency", "USD")))).isEqualTo(422);
    assertThat(statusOf(pspPost("/v1/captures", "demo-psp-key", "c4").body(Map.of("authRef", authRef, "amountMinor", 100, "currency", "EUR")))).isEqualTo(422);
    assertThat(statusOf(pspPost("/v1/voids", "demo-psp-key", "v1").body(Map.of("authRef", "auth_nope")))).isEqualTo(404);
    JsonNode voided = json(pspPost("/v1/voids", "demo-psp-key", "v2").body(Map.of("authRef", authRef)));
    assertThat(voided.path("authRef").asText()).isEqualTo(authRef);
  }

  @Test
  void aRepeatOfAnApprovedKeyGetsItsFirstAnswerEvenWhileADeclineIsArmed() {
    String first = authorise("a3", 100);
    HTTP.post().uri(psp.url() + "/sim/faults").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("fault", "DECLINE_NEXT")).retrieve().toBodilessEntity();
    assertThat(authorise("a3", 100)).as("a processor answers a repeat from its record, not with a fresh decision").isEqualTo(first);
    assertThat(json(HTTP.get().uri(psp.url() + "/sim/state")).path("fault").asText()).as("the decline is still waiting for a new request").isEqualTo("DECLINE_NEXT");
  }

  @Test
  void anUnknownFaultIsABadRequestNotACrash() {
    assertThat(statusOf(HTTP.post().uri(psp.url() + "/sim/faults").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("fault", "NOPE")))).isEqualTo(400);
    assertThat(statusOf(HTTP.post().uri(opera.url() + "/sim/faults").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("fault", "NOPE")))).isEqualTo(400);
  }

  private String authorise(String key, long amount) {
    Map<String, Object> auth = Map.of("pan", "4111111111111111", "expiry", "12/29", "amountMinor", amount, "currency", "USD");
    return json(pspPost("/v1/authorizations", "demo-psp-key", key).body(auth)).path("ref").asText();
  }

  /** Fails if the simulator answers a reused key with the first result whatever the request: the bridge could never meet a real 409. */
  @Test
  void aKeyReusedForADifferentAmountIsAConflictNotTheFirstAnswer() {
    authorise("a9", 100);
    assertThat(statusOf(pspPost("/v1/authorizations", "demo-psp-key", "a9").body(Map.of("pan", "4111111111111111", "expiry", "12/29", "amountMinor", 999, "currency", "USD"))))
        .isEqualTo(409);
  }

  @Test
  void theHotelsWebhookReceiverRefusesAnUnsignedEvent() {
    int status = statusOf(HTTP.post().uri(opera.url() + "/sim/webhooks").header("Webhook-Id", "x").header("Webhook-Timestamp", String.valueOf(java.time.Instant.now().getEpochSecond()))
        .header("Webhook-Signature", "v1=00").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("{}"));
    assertThat(status).isEqualTo(401);
  }
}

