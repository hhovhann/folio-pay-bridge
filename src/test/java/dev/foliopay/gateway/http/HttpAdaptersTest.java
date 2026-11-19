package dev.foliopay.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.gateway.OperaClient.OperaPayment;
import dev.foliopay.gateway.OperaRejectedException;
import dev.foliopay.gateway.OperaUnavailableException;
import dev.foliopay.gateway.PspDeclinedException;
import dev.foliopay.gateway.PspTransientException;
import dev.foliopay.integration.SimulatorHarness;
import dev.foliopay.integration.SimulatorHarness.Reply;
import dev.foliopay.integration.SimulatorHarness.Sim;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The HTTP adapters against the simulators and against stubs that answer with exactly one awkward status. */
@DisplayName("HTTP adapters: against the simulators and against one awkward status at a time")
class HttpAdaptersTest {
  private static final RestClient CONTROL = RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory()).build(); // HTTP/1.1, like the real adapters: the JDK client may retry a cleartext POST over h2c
  private static Sim opera;
  private static Sim psp;
  /** The simulator's own business date, read after each reset: a date computed once at class load breaks across Dubai midnight. */
  private static LocalDate day;

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
    CONTROL.post().uri(opera.url() + "/sim/reset").retrieve().toBodilessEntity();
    CONTROL.post().uri(psp.url() + "/sim/reset").retrieve().toBodilessEntity();
    day = LocalDate.parse(CONTROL.get().uri(opera.url() + "/sim/state").retrieve().body(com.fasterxml.jackson.databind.JsonNode.class).path("businessDate").asText());
  }

  private HttpGatewayProperties props(String operaUrl, String pspUrl, String clientSecret, String pspKey) {
    return new HttpGatewayProperties(operaUrl, "demo-app-key", "fpbridge", clientSecret, "DEMOENT", "demo-scope", pspUrl, pspKey,
        Duration.ofSeconds(1), Duration.ofSeconds(1));
  }

  private HttpOperaClient operaClient(String url) { return new HttpOperaClient(props(url, psp.url(), "fpbridge-secret", "demo-psp-key"), RestClient.builder(), java.time.Clock.systemUTC()); }

  private HttpPspClient pspClient(String url, String key) { return new HttpPspClient(props(opera.url(), url, "fpbridge-secret", key), RestClient.builder()); }

  private static OperaPayment payment(String reservation, Money amount) {
    return new OperaPayment("DEMO1", reservation, 1, "CC", 9001, amount, "ref-" + reservation + amount.currency(), "cap_1");
  }

  @Test
  @DisplayName("The HTTP settings hide their credentials when printed")
  void theSettingsHideTheirCredentialsWhenPrinted() {
    assertThat(props("http://x", "http://y", "super-secret", "key-123").toString()).doesNotContain("super-secret").doesNotContain("key-123");
  }

  // ---- OPERA
  /** Fails if wrong credentials dead-letter postings as rejections: those are never replayed, though the folios are fine (review finding). */
  @Test
  @DisplayName("Wrong OPERA credentials are ours to fix and replayable, not a rejection of the folio")
  void wrongCredentialsAreOursToFixAndReplayableNotARejectionOfTheFolio() {
    HttpOperaClient client = new HttpOperaClient(props(opera.url(), psp.url(), "wrong-secret", "k"), RestClient.builder(), java.time.Clock.systemUTC());
    assertThatThrownBy(() -> client.businessDate("DEMO1")).isInstanceOf(OperaUnavailableException.class).isNotInstanceOf(OperaRejectedException.class)
        .hasMessageContaining("refused our credentials");
  }

  @Test
  @DisplayName("An OPERA that cannot be reached is unavailable at the token step")
  void anOperaThatCannotBeReachedIsUnavailableAtTheTokenStep() {
    assertThatThrownBy(() -> operaClient("http://127.0.0.1:1").businessDate("DEMO1")).isInstanceOf(OperaUnavailableException.class).hasMessageContaining("token service");
  }

  @Test
  @DisplayName("A reservation that does not exist is rejected with OPERA's reason")
  void aReservationThatDoesNotExistIsRejectedWithOperasReason() {
    assertThatThrownBy(() -> operaClient(opera.url()).postPayment(payment("GHOST-1", new Money(1_000, "USD"))))
        .isInstanceOf(OperaRejectedException.class).hasMessageContaining("RESERVATION_NOT_FOUND");
  }

  @Test
  @DisplayName("A payment is found back with its amount restored to minor units in each currency")
  void aPaymentIsFoundBackWithItsAmountRestoredToMinorUnitsInEachCurrency() {
    HttpOperaClient client = operaClient(opera.url());
    client.postPayment(payment("RES-1", new Money(12_345, "USD")));
    client.postPayment(payment("RES-1", new Money(1_234, "KWD")));
    client.postPayment(payment("RES-1", new Money(500, "JPY")));

    assertThat(client.find("DEMO1", "RES-1", "ref-RES-1USD").orElseThrow().amount()).contains(new Money(12_345, "USD"));
    assertThat(client.find("DEMO1", "RES-1", "ref-RES-1KWD").orElseThrow().amount()).as("three decimals").contains(new Money(1_234, "KWD"));
    assertThat(client.find("DEMO1", "RES-1", "ref-RES-1JPY").orElseThrow().amount()).as("no decimals").contains(new Money(500, "JPY"));
    assertThat(client.find("DEMO1", "RES-1", "never-posted")).isEmpty();
    assertThat(client.businessDate("DEMO1")).isEqualTo(day);
  }

  @Test
  @DisplayName("A token that is always refused is reported as \"wait\", not as a bad request")
  void aTokenThatIsAlwaysRefusedIsReportedAsWaitNotAsABadRequest() {
    try (Sim shortLived = SimulatorHarness.start("opera", "--sim.opera.token-ttl-seconds=0")) {
      assertThatThrownBy(() -> operaClient(shortLived.url()).businessDate("DEMO1")).isInstanceOf(OperaUnavailableException.class).hasMessageContaining("401");
    }
  }

  @Test
  @DisplayName("A server error from OPERA is unavailable; a client error is a rejection, with or without a reason")
  void aServerErrorFromOperaIsUnavailableAndAClientErrorIsARejectionWithOrWithoutAReason() {
    try (var down = SimulatorHarness.stub((m, path) -> path.contains("tokens") ? new Reply(200, "{\"access_token\":\"t\",\"expires_in\":60}") : new Reply(503, "{}"))) {
      assertThatThrownBy(() -> operaClient(down.url()).businessDate("DEMO1")).isInstanceOf(OperaUnavailableException.class).hasMessageContaining("503");
    }
    try (var coded = SimulatorHarness.stub((m, path) -> path.contains("tokens") ? new Reply(200, "{\"access_token\":\"t\",\"expires_in\":60}") : new Reply(400, "{\"code\":\"BAD_THING\"}"))) {
      assertThatThrownBy(() -> operaClient(coded.url()).businessDate("DEMO1")).isInstanceOf(OperaRejectedException.class).hasMessageContaining("BAD_THING");
    }
    try (var bare = SimulatorHarness.stub((m, path) -> path.contains("tokens") ? new Reply(200, "{\"access_token\":\"t\",\"expires_in\":60}") : new Reply(400, ""))) {
      assertThatThrownBy(() -> operaClient(bare.url()).businessDate("DEMO1")).isInstanceOf(OperaRejectedException.class).hasMessageContaining("no reason given");
    }
  }

  // ---- processor
  @Test
  @DisplayName("Authorise, capture, void and the settlement file work, and repeats return the same reference")
  void authoriseCaptureVoidAndTheSettlementFileWorkAndRepeatsReturnTheSameReference() {
    HttpPspClient client = pspClient(psp.url(), "demo-psp-key");
    var auth = client.authorize("k:auth", new Pan("4111111111111111"), "12/29", new Money(5_000, "USD"));
    assertThat(client.authorize("k:auth", new Pan("4111111111111111"), "12/29", new Money(5_000, "USD"))).as("same key, same result").isEqualTo(auth);
    var capture = client.capture("k:cap", auth.ref(), new Money(5_000, "USD"));
    client.voidAuthorization("k:void", auth.ref());

    assertThat(client.settlementStatement()).singleElement().satisfies(line -> {
      assertThat(line.pspRef()).isEqualTo(capture.ref());
      assertThat(line.amountMinor()).isEqualTo(5_000);
      assertThat(line.currency()).isEqualTo("USD");
    });
  }

  @Test
  @DisplayName("A decline is final, and a processor that cannot be reached is transient")
  void aDeclineIsFinalAndAProcessorThatCannotBeReachedIsTransient() {
    CONTROL.post().uri(psp.url() + "/sim/faults").body(Map.of("fault", "DECLINE_NEXT")).retrieve().toBodilessEntity();
    assertThatThrownBy(() -> pspClient(psp.url(), "demo-psp-key").authorize("d", new Pan("4111111111111111"), "12/29", new Money(1, "USD"))).isInstanceOf(PspDeclinedException.class);
    assertThatThrownBy(() -> pspClient("http://127.0.0.1:1", "k").capture("c", "a", new Money(1, "USD"))).isInstanceOf(PspTransientException.class).hasMessageContaining("did not answer");
  }

  @Test
  @DisplayName("A wrong processor API key is our bug, not a retry, and the message never carries the card number")
  void aWrongApiKeyIsOurBugNotARetryAndTheMessageNeverCarriesTheCardNumber() {
    assertThatThrownBy(() -> pspClient(psp.url(), "wrong").authorize("k", new Pan("4111111111111111"), "12/29", new Money(1, "USD")))
        .isInstanceOf(PspTransientException.class).hasMessageContaining("401").message().doesNotContain("4111");
  }

  @Test
  @DisplayName("A server error from the processor is transient")
  void aServerErrorFromTheProcessorIsTransient() {
    try (var broken = SimulatorHarness.stub((m, path) -> new Reply(503, "{}"))) {
      assertThatThrownBy(() -> pspClient(broken.url(), "k").capture("c", "a", new Money(1, "USD"))).isInstanceOf(PspTransientException.class).hasMessageContaining("503");
    }
  }

  @Test
  @DisplayName("The settlement file is parsed from whatever the processor sends")
  void theSettlementFileIsParsedFromWhateverTheProcessorSends() {
    try (var stub = SimulatorHarness.stub((m, path) -> new Reply(200, "{\"lines\":[{\"pspRef\":\"cap_1\",\"amountMinor\":250,\"currency\":\"EUR\"}]}"))) {
      assertThat(pspClient(stub.url(), "k").settlementStatement()).singleElement().satisfies(l -> assertThat(l.currency()).isEqualTo("EUR"));
    }
  }
}
