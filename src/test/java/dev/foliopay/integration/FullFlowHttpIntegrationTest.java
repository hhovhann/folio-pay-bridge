package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.domain.model.ReconReport;
import dev.foliopay.service.BusinessDateService;
import dev.foliopay.service.CardVault;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.Outcome;
import dev.foliopay.service.PaymentService.PaymentCommand;
import dev.foliopay.service.PostingService;
import dev.foliopay.service.ReconciliationService;
import dev.foliopaysim.SimulatorApplication;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * The whole flow over real HTTP: the bridge talks to an OPERA simulator and a processor simulator started on real ports. Unlike the tests
 * against in-memory fakes, this exercises the adapters: OAuth, timeouts, 429, status mapping, the idempotency header, look-before-repost.
 */
@SpringBootTest(properties = {
    "foliopay.keys=local", "foliopay.gateway=http", "foliopay.demo-controls=false",
    "foliopay.retry.initial-backoff=10ms", "foliopay.http.read-timeout=1s", "foliopay.http.connect-timeout=1s",
    "foliopay.http.opera-app-key=demo-app-key", "foliopay.http.opera-client-id=fpbridge", "foliopay.http.opera-client-secret=fpbridge-secret",
    "foliopay.http.psp-api-key=demo-psp-key", "spring.datasource.url=jdbc:h2:mem:fullflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "foliopay.auth.api-keys=tests:0000000000000000000000000000000000000000000000000000000000000000:payments:read"}) // a real gateway needs API keys
class FullFlowHttpIntegrationTest {
  private static ConfigurableApplicationContext operaSim;
  private static ConfigurableApplicationContext pspSim;
  private static String operaUrl;
  private static String pspUrl;
  private static final RestClient CONTROL = RestClient.create();
  private static final String HOTEL = "DEMO1";
  private static final String WEBHOOK_SECRET = "whsec_fullflow_0123456789abcdef0123";
  @Autowired private dev.foliopay.service.NotificationService notifications;

  @BeforeAll
  static void startTheOutsideWorld() {
    operaSim = start("opera");
    pspSim = start("psp");
    operaUrl = "http://localhost:" + operaSim.getEnvironment().getProperty("local.server.port");
    pspUrl = "http://localhost:" + pspSim.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stopTheOutsideWorld() {
    operaSim.close();
    pspSim.close();
  }

  private static ConfigurableApplicationContext start(String profile) {
    return new SpringApplicationBuilder(SimulatorApplication.class).web(WebApplicationType.SERVLET).profiles(profile)
        .run("--server.port=0", "--spring.main.banner-mode=off", "--sim.zone=Asia/Dubai", "--sim.opera.webhook-secret=" + WEBHOOK_SECRET);
  }

  @DynamicPropertySource
  static void pointTheBridgeAtTheSimulators(DynamicPropertyRegistry registry) {
    registry.add("foliopay.webhook.url", () -> operaUrl + "/sim/webhooks"); // the hotel's middleware, played by the OPERA simulator
    registry.add("foliopay.webhook.secret", () -> WEBHOOK_SECRET);
    registry.add("foliopay.webhook.allow-plain-http", () -> "true");
    registry.add("foliopay.webhook.poll", () -> "1h"); // the test drives the relay
    registry.add("foliopay.http.opera-base-url", () -> operaUrl);
    registry.add("foliopay.http.psp-base-url", () -> pspUrl);
  }

  @Autowired CardVault vault;
  @Autowired PaymentService payments;
  @Autowired PostingService postings;
  @Autowired ReconciliationService reconciliation;
  @Autowired BusinessDateService businessDates;
  @Autowired dev.foliopay.repository.ChargeRepository chargeRepository;
  @Autowired dev.foliopay.repository.PostingRepository postingRepository;
  private String token;

  @BeforeEach
  void freshWorld() {
    postingRepository.deleteAll();
    chargeRepository.deleteAll();
    CONTROL.post().uri(operaUrl + "/sim/reset").retrieve().toBodilessEntity();
    CONTROL.post().uri(pspUrl + "/sim/reset").retrieve().toBodilessEntity();
    token = vault.tokenise(new Pan("4111111111111111"), "12/29").getToken();
  }

  private void fault(String simUrl, String fault) {
    CONTROL.post().uri(simUrl + "/sim/faults").body(Map.of("fault", fault)).retrieve().toBodilessEntity();
  }

  private JsonNode state(String simUrl) { return CONTROL.get().uri(simUrl + "/sim/state").retrieve().body(JsonNode.class); }

  private PaymentService.Result pay(String reservation, long amount) {
    LocalDate day = LocalDate.parse(state(operaUrl).path("businessDate").asText());
    return payments.pay(PaymentCommand.bridge("k-" + UUID.randomUUID(), "BK-1", reservation, token, new Money(amount, "USD"), day));
  }

  @Test
  void aPaymentIsAuthorisedCapturedAndPostedToTheReservationFolioOverHttp() {
    var result = pay("RES-1", 12_345);

    assertThat(result.outcome()).isEqualTo(Outcome.OK);
    JsonNode payment = state(operaUrl).path("payments").get(0);
    assertThat(payment.path("hotelId").asText()).isEqualTo(HOTEL);
    assertThat(payment.path("reservationId").asText()).isEqualTo("RES-1");
    assertThat(payment.path("folioWindow").asInt()).isEqualTo(1);
    assertThat(payment.path("paymentCode").asText()).isEqualTo("CC");
    assertThat(payment.path("amount").asText()).as("OPERA takes a decimal string, not minor units").isEqualTo("123.45");
    assertThat(payment.path("reference").asText()).isEqualTo(result.posting().getIdempotencyKey());
    assertThat(payment.path("pspRef").asText()).isEqualTo(result.charge().getPspRef());
    assertThat(state(pspUrl).path("ledger")).extracting(e -> e.path("kind").asText()).containsExactly("AUTH", "CAPTURE");
  }

  @Test
  void theCardNumberReachesOnlyTheProcessorAndOnlyItsLastFourAreKept() {
    pay("RES-1", 5_000);
    assertThat(state(pspUrl).toString()).contains("1111").doesNotContain("4111111111111111");
    assertThat(state(operaUrl).toString()).doesNotContain("4111111111111111").doesNotContain("4111");
  }

  @Test
  void anExpiredAccessTokenIsReplacedOnceWithoutFailingThePayment() {
    pay("RES-1", 1_000);
    int issuedBefore = state(operaUrl).path("tokensIssued").asInt();
    CONTROL.post().uri(operaUrl + "/sim/expire-tokens").retrieve().toBodilessEntity();

    assertThat(pay("RES-2", 2_000).outcome()).isEqualTo(Outcome.OK);

    assertThat(state(operaUrl).path("tokensIssued").asInt()).isGreaterThan(issuedBefore);
    assertThat(state(operaUrl).path("payments")).hasSize(2);
  }

  @Test
  void aReplyLostAfterOperaPostedIsFoundAgainNotPostedTwice() {
    fault(operaUrl, "TIMEOUT_AFTER_POST"); // OPERA records the payment, then answers after our read timeout

    var result = pay("RES-1", 7_000);

    assertThat(result.outcome()).isEqualTo(Outcome.OK);
    assertThat(state(operaUrl).path("fault").asText()).as("the fault was really hit, not skipped").isEqualTo("NONE");
    assertThat(state(operaUrl).path("payments")).as("OPERA does not deduplicate, so exactly one means the bridge looked before it retried").hasSize(1);
  }

  @Test
  void aRateLimitIsWaitedOutAndRetried() {
    fault(operaUrl, "THROTTLE_NEXT");
    assertThat(pay("RES-1", 3_000).outcome()).isEqualTo(Outcome.OK);
    assertThat(state(operaUrl).path("fault").asText()).as("the fault was really hit, not skipped").isEqualTo("NONE");
    assertThat(state(operaUrl).path("payments")).hasSize(1);
  }

  @Test
  void aLockConflictOnTheReservationIsRetried() {
    fault(operaUrl, "LOCK_CONFLICT_NEXT");
    assertThat(pay("RES-1", 3_000).outcome()).isEqualTo(Outcome.OK);
    assertThat(state(operaUrl).path("fault").asText()).as("the fault was really hit, not skipped").isEqualTo("NONE");
    assertThat(state(operaUrl).path("payments")).hasSize(1);
  }

  @Test
  void anOperaOutageDeadLettersTheCapturedPaymentAndReplayPostsItOnceOperaIsBack() {
    fault(operaUrl, "DOWN");
    var result = pay("RES-1", 40_000);

    assertThat(result.outcome()).isEqualTo(Outcome.CHARGED_NOT_POSTED);
    assertThat(result.posting().getFailureCategory()).isEqualTo(Posting.FailureCategory.OPERA_UNAVAILABLE);
    assertThat(state(pspUrl).path("capturedMinor").asLong()).as("the money was taken").isEqualTo(40_000);

    fault(operaUrl, "NONE");
    assertThat(postings.replay(result.posting().getId()).getStatus()).isEqualTo(Posting.Status.POSTED);
    postings.replay(result.posting().getId());
    assertThat(state(operaUrl).path("payments")).hasSize(1);
  }

  /**
   * Review finding H2, over the network against an OPERA that does not deduplicate: eight replays at once (an operator clicking Replay,
   * a retrying client) must leave one payment on the folio, not one per replay that looked before another had posted.
   */
  @Test
  void parallelReplaysAgainstANonDeduplicatingOperaPostOnce() throws Exception {
    fault(operaUrl, "DOWN");
    String postingId = pay("RES-1", 12_000).posting().getId();
    fault(operaUrl, "NONE");

    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.List<java.util.concurrent.Future<Posting>> running = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      running.add(pool.submit(() -> {
        start.await();
        return postings.replay(postingId);
      }));
    }
    start.countDown();
    for (var done : running) done.get(); // none may fail
    pool.shutdown();

    assertThat(state(operaUrl).path("payments")).as("one payment, one folio entry").hasSize(1);
    assertThat(postingRepository.findById(postingId).orElseThrow().getStatus()).isEqualTo(Posting.Status.POSTED);
  }

  @Test
  void aClosedFolioIsRejectedWithOperasReasonAndReplayLeavesItAlone() {
    var result = pay("CLOSED-77", 9_900);

    assertThat(result.posting().getFailureCategory()).isEqualTo(Posting.FailureCategory.REJECTED_BY_OPERA);
    assertThat(result.posting().getLastError()).contains("FOLIO_CLOSED");
    postings.replay(result.posting().getId());
    assertThat(state(operaUrl).path("payments")).isEmpty();
  }

  @Test
  void operaRefusesACallWithoutTheApplicationKeyOrTheHotelHeader() {
    String token = CONTROL.post().uri(operaUrl + "/oauth/v1/tokens").header("x-app-key", "demo-app-key")
        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
        .body("grant_type=client_credentials&client_id=fpbridge&client_secret=fpbridge-secret").retrieve().body(JsonNode.class).path("access_token").asText();
    var business = operaUrl + "/v1/hotels/" + HOTEL + "/business-date";
    assertThat(statusOf(business, token, null, HOTEL)).as("no x-app-key").isEqualTo(401);
    assertThat(statusOf(business, token, "demo-app-key", "OTHER")).as("x-hotelid names another hotel").isEqualTo(400);
    assertThat(statusOf(business, token, "demo-app-key", HOTEL)).isEqualTo(200);
  }

  private int statusOf(String url, String bearer, String appKey, String hotel) {
    return CONTROL.get().uri(url).header("Authorization", "Bearer " + bearer).headers(h -> { if (appKey != null) h.add("x-app-key", appKey); h.add("x-hotelid", hotel); })
        .exchange((request, response) -> response.getStatusCode().value());
  }

  @Test
  void theBusinessDateComesFromOperaAndMovesOnlyAtNightAudit() {
    LocalDate before = businessDates.resolve(null);
    assertThatThrownBy(() -> businessDates.resolve(before.minusDays(1))).isInstanceOf(InvalidRequestException.class);

    CONTROL.post().uri(operaUrl + "/sim/night-audit").retrieve().toBodilessEntity();

    assertThat(businessDates.resolve(null)).isEqualTo(before.plusDays(1));
    assertThatThrownBy(() -> businessDates.resolve(before)).as("yesterday's date is refused after the audit").isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void whenOperaIsDownThePaymentIsStillTakenUsingTheLastKnownBusinessDateAndThePostingWaits() {
    LocalDate known = businessDates.resolve(null);
    fault(operaUrl, "DOWN");

    assertThat(businessDates.resolve(null)).isEqualTo(known);
    var result = payments.pay(PaymentCommand.bridge("k-" + UUID.randomUUID(), "BK-1", "RES-1", token, new Money(6_000, "USD"), businessDates.resolve(null)));

    assertThat(result.outcome()).isEqualTo(Outcome.CHARGED_NOT_POSTED);
    assertThat(state(pspUrl).path("capturedMinor").asLong()).isEqualTo(6_000);
  }

  @Test
  void aProcessorThatCapturesThenGoesQuietIsRetriedWithTheSameKeyAndChargesOnce() {
    fault(pspUrl, "TIMEOUT_AFTER_CAPTURE");

    var result = pay("RES-1", 18_000);

    assertThat(result.outcome()).isEqualTo(Outcome.OK);
    assertThat(state(pspUrl).path("fault").asText()).as("the fault was really hit, not skipped").isEqualTo("NONE");
    assertThat(state(pspUrl).path("ledger")).filteredOn(e -> e.path("kind").asText().equals("CAPTURE")).hasSize(1);
    assertThat(state(pspUrl).path("capturedMinor").asLong()).isEqualTo(18_000);
  }

  @Test
  void aDeclineIsFinalAndNothingReachesTheFolio() {
    fault(pspUrl, "DECLINE_NEXT");
    var result = pay("RES-1", 4_000);
    assertThat(result.outcome()).isEqualTo(Outcome.DECLINED);
    assertThat(state(operaUrl).path("payments")).isEmpty();
  }

  @Test
  void aCaptureDeclinedAfterAuthorisationReleasesTheHoldAtTheProcessor() {
    fault(pspUrl, "DECLINE_CAPTURE_NEXT");
    assertThat(pay("RES-1", 4_000).outcome()).isEqualTo(Outcome.DECLINED);
    var ledger = state(pspUrl).path("ledger");
    assertThat(ledger).extracting(e -> e.path("kind").asText()).contains("AUTH", "VOID");
    String authorised = java.util.stream.StreamSupport.stream(ledger.spliterator(), false).filter(e -> e.path("kind").asText().equals("AUTH")).reduce((a, b) -> b).orElseThrow().path("ref").asText();
    String released = java.util.stream.StreamSupport.stream(ledger.spliterator(), false).filter(e -> e.path("kind").asText().equals("VOID")).findFirst().orElseThrow().path("authRef").asText();
    assertThat(released).as("the hold released is the one that was authorised").isEqualTo(authorised);
  }

  @Test
  void reconciliationAgainstTheProcessorsOwnSettlementFileMatchesEverythingPosted() {
    pay("RES-1", 1_000);
    pay("RES-2", 2_500);

    ReconReport report = reconciliation.reconcileAcquirerStatement(false, ReconciliationService.Period.ALL);

    assertThat(report.exceptions()).isEmpty();
    assertThat(report.matched()).isEqualTo(2);
    assertThat(report.totals()).containsOnlyKeys("USD");
    assertThat(report.totals().get("USD").settledMinor()).isEqualTo(3_500);
  }

  /** End to end over HTTP: the hotel's side verifies the signature (it answers 401 otherwise) and receives each event once. */
  @Test
  void theHotelsMiddlewareReceivesSignedCaptureAndPostingEventsOverHttp() {
    pay("RES-WH", 3_300);

    notifications.collect();
    notifications.deliverDue();

    JsonNode received = CONTROL.get().uri(operaUrl + "/sim/webhooks").retrieve().body(JsonNode.class);
    assertThat(received).hasSize(2);
    assertThat(received.findValuesAsText("id")).anyMatch(id -> id.startsWith("payment.captured:")).anyMatch(id -> id.startsWith("folio.posted:"));
    assertThat(received.toString()).doesNotContain("4111111111111111");
  }
}

