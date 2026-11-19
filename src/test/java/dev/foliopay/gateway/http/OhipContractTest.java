package dev.foliopay.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.domain.model.Money;
import dev.foliopay.gateway.OperaClient.OperaPayment;
import dev.foliopay.gateway.OperaRejectedException;
import dev.foliopay.gateway.OperaUnavailableException;
import dev.foliopay.gateway.http.OhipSpec.Exchange;
import dev.foliopay.integration.SimulatorHarness;
import dev.foliopay.integration.SimulatorHarness.Sim;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The OPERA adapter and the OPERA simulator, both held to Oracle's published OHIP contract (D-42). Every HTTP exchange the adapter makes
 * with the simulator, in the happy path and in each failure, is recorded and checked against Oracle's Swagger files: the request the
 * adapter sends (paths, headers, query, form, body) and the reply the simulator gives (status and body). A drift on either side fails here:
 * the adapter inventing a field, or the simulator answering in a shape real OPERA would not.
 */
@DisplayName("OHIP contract: the OPERA adapter and simulator held to Oracle's published specification (D-42)")
class OhipContractTest {
  private static final RestClient CONTROL = RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory()).build();
  private static final OhipSpec OHIP = new OhipSpec();
  private static Sim opera;
  private final List<Exchange> exchanges = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void start() {
    opera = SimulatorHarness.start("opera");
  }

  @AfterAll
  static void stop() {
    opera.close();
  }

  @BeforeEach
  void reset() {
    CONTROL.post().uri(opera.url() + "/sim/reset").retrieve().toBodilessEntity();
    exchanges.clear();
  }

  /** Whatever the scenario, nothing the adapter sent or the simulator answered may depart from Oracle's contract. */
  @AfterEach
  void everyExchangeConformsToOraclesContract() {
    assertThat(exchanges).as("the scenario talked to OPERA").isNotEmpty();
    assertThat(exchanges.stream().flatMap(e -> OHIP.check(e).stream()).toList()).isEmpty();
  }

  private HttpOperaClient client() {
    HttpGatewayProperties settings = new HttpGatewayProperties(opera.url(), "demo-app-key", "fpbridge", "fpbridge-secret", "DEMOENT", "demo-scope",
        "http://unused", "unused", Duration.ofSeconds(2), Duration.ofSeconds(6));
    RestClient.Builder recording = RestClient.builder().requestInterceptor((request, body, execution) -> {
      ClientHttpResponse response = execution.execute(request, body);
      byte[] reply = response.getBody().readAllBytes();
      exchanges.add(new Exchange(request.getMethod().name(), request.getURI(), HttpHeaders.readOnlyHttpHeaders(request.getHeaders()),
          new String(body, StandardCharsets.UTF_8), response.getStatusCode().value(), new String(reply, StandardCharsets.UTF_8)));
      return new Replayed(response, reply);
    });
    return new HttpOperaClient(settings, recording, Clock.systemUTC());
  }

  private static OperaPayment payment(String reservation, Money amount, String reference) {
    return new OperaPayment("DEMO1", reservation, 1, "CC", 9001, amount, reference, "cap_1");
  }

  private List<String> operationsUsed() {
    return exchanges.stream().map(e -> OHIP.operation(e.method(), e.uri().getPath()).map(OhipSpec.Operation::id).orElse("?")).distinct().toList();
  }

  @Test
  @DisplayName("A payment is posted and found back through Oracle's operations in each currency")
  void aPaymentIsPostedAndFoundBackThroughOraclesOperationsInEachCurrency() {
    HttpOperaClient client = client();
    client.businessDate("DEMO1");
    String usd = client.postPayment(payment("RES-1", new Money(12_345, "USD"), "post:usd"));
    client.postPayment(payment("RES-1", new Money(1_234, "KWD"), "post:kwd"));
    client.postPayment(payment("RES-1", new Money(500, "JPY"), "post:jpy"));
    client.postPayment(payment("RES-1", new Money(80_000, "USD"), "post:round"));

    assertThat(client.find("DEMO1", "RES-1", "post:usd").orElseThrow().operaRef()).isEqualTo(usd);
    assertThat(client.find("DEMO1", "RES-1", "post:usd").orElseThrow().amount()).contains(new Money(12_345, "USD"));
    assertThat(client.find("DEMO1", "RES-1", "post:kwd").orElseThrow().amount()).as("three decimals").contains(new Money(1_234, "KWD"));
    assertThat(client.find("DEMO1", "RES-1", "post:jpy").orElseThrow().amount()).as("no decimals").contains(new Money(500, "JPY"));
    // Fails if JSON decimals lose their trailing zeros (Jackson's default turns 800.00 into 8E+2): found during this phase, both sides.
    assertThat(client.find("DEMO1", "RES-1", "post:round").orElseThrow().amount()).as("800.00").contains(new Money(80_000, "USD"));
    assertThat(client.find("DEMO1", "RES-1", "never-posted")).isEmpty();
    assertThat(client.find("DEMO1", "RES-2", "post:usd")).as("another reservation's folio").isEmpty();
    assertThat(operationsUsed()).containsExactlyInAnyOrder("getToken", "getBusinessDate", "postBillingPayment", "getFolio");
  }

  /**
   * A long folio comes in pages: the adapter asks for a window's next page by Oracle's {@code folioWindowNo} and {@code offset}, and reads on
   * to our payment or the last page; every page, asked and answered, is held to the contract. The simulator's page size is its own choice.
   */
  @Test
  @DisplayName("A payment on a long folio is found on a later page")
  void aPaymentOnALongFolioIsFoundOnALaterPage() {
    CONTROL.post().uri(opera.url() + "/sim/folio-page-size").contentType(MediaType.APPLICATION_JSON).body(Map.of("size", 2))
        .retrieve().toBodilessEntity();
    HttpOperaClient client = client();
    for (int i = 1; i <= 5; i++) {
      client.postPayment(payment("RES-1", new Money(1_000 + i, "USD"), "post:page-" + i));
    }
    assertThat(client.find("DEMO1", "RES-1", "post:page-5").orElseThrow().amount()).as("the fifth of five, on the third page")
        .contains(new Money(1_005, "USD"));
    assertThat(client.find("DEMO1", "RES-1", "never-posted")).as("read to the last page").isEmpty();
    assertThat(exchanges).extracting(e -> String.valueOf(e.uri().getQuery())).contains("fetchInstructions=Postings&folioWindowNo=1&offset=2",
        "fetchInstructions=Postings&folioWindowNo=1&offset=4");
  }

  /** OPERA posts on its own business date: a payment after night audit lands on the new day, and the bridge reads that date back. */
  @Test
  @DisplayName("A payment lands on OPERA's business date, which moves only at night audit")
  void aPaymentLandsOnOperasBusinessDateWhichMovesOnlyAtNightAudit() {
    HttpOperaClient client = client();
    var before = client.businessDate("DEMO1");
    CONTROL.post().uri(opera.url() + "/sim/night-audit").retrieve().toBodilessEntity();
    client.postPayment(payment("RES-1", new Money(1_000, "USD"), "post:after-audit"));
    assertThat(client.businessDate("DEMO1")).isEqualTo(before.plusDays(1));
    assertThat(client.find("DEMO1", "RES-1", "post:after-audit").orElseThrow().businessDate()).contains(before.plusDays(1));
  }

  @Test
  @DisplayName("OPERA's own refusals are rejections carrying its error code")
  void operasOwnRefusalsAreRejectionsCarryingItsErrorCode() {
    HttpOperaClient client = client();
    assertThatThrownBy(() -> client.postPayment(payment("GHOST-1", new Money(1_000, "USD"), "post:ghost")))
        .isInstanceOf(OperaRejectedException.class).hasMessageContaining("RESERVATION_NOT_FOUND");
    assertThatThrownBy(() -> client.postPayment(payment("CLOSED-9", new Money(1_000, "USD"), "post:closed")))
        .isInstanceOf(OperaRejectedException.class).hasMessageContaining("FOLIO_CLOSED");
    assertThatThrownBy(() -> client.postPayment(new OperaPayment("DEMO1", "RES-1", 1, "ZZ", 9001, new Money(1_000, "USD"), "post:method", "cap_1")))
        .isInstanceOf(OperaRejectedException.class).hasMessageContaining("INVALID_PAYMENT");
  }

  /** The request id of the API call that caused a posting goes to OPERA, so Oracle support can find the call in their logs. */
  @Test
  @DisplayName("The request id travels to OPERA when there is one")
  void theRequestIdTravelsToOperaWhenThereIsOne() {
    HttpOperaClient client = client();
    client.businessDate("DEMO1");
    MDC.put("requestId", "req-42");
    try {
      client.businessDate("DEMO1");
    } finally {
      MDC.remove("requestId");
    }
    assertThat(exchanges).extracting(e -> String.valueOf(e.requestHeaders().getFirst("x-request-id"))).containsExactly("null", "null", "req-42");
  }

  @Test
  @DisplayName("An expired token is replaced once, and an outage is unavailable")
  void anExpiredTokenIsReplacedOnceAndAnOutageIsUnavailable() {
    HttpOperaClient client = client();
    client.businessDate("DEMO1");
    CONTROL.post().uri(opera.url() + "/sim/expire-tokens").retrieve().toBodilessEntity();
    client.businessDate("DEMO1");
    assertThat(exchanges.stream().filter(e -> e.status() == 401)).as("the expired token was refused once").hasSize(1);
    assertThat(exchanges.stream().filter(e -> e.uri().getPath().endsWith("/tokens"))).as("and replaced").hasSize(2);

    CONTROL.post().uri(opera.url() + "/sim/faults").contentType(MediaType.APPLICATION_JSON).body(Map.of("fault", "DOWN"))
        .retrieve().toBodilessEntity();
    assertThatThrownBy(() -> client.postPayment(payment("RES-1", new Money(1_000, "USD"), "post:down")))
        .isInstanceOf(OperaUnavailableException.class).hasMessageContaining("503");
  }

  /** Proves the check has teeth: the shapes this bridge sent before it followed Oracle's contract are refused. */
  @Test
  @DisplayName("The contract check refuses the invented shapes the bridge used before D-42")
  void theCheckRefusesTheInventedShapesTheBridgeUsedBeforeD42() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("t");
    headers.add("x-app-key", "k");
    headers.add("x-hotelid", "DEMO1");
    headers.setContentType(MediaType.APPLICATION_JSON);
    String oldBody = "{\"folioWindow\":1,\"paymentCode\":\"CC\",\"cashierId\":\"FPBRIDGE\",\"amount\":\"123.45\",\"currency\":\"USD\","
        + "\"businessDate\":\"2026-11-18\",\"reference\":\"post:c1\"}";
    String oldReply = "{\"transactionNo\":\"TX-1000\"}";
    assertThat(OHIP.check(new Exchange("POST", URI.create("http://h/v1/hotels/DEMO1/reservations/RES-1/payments"), headers, oldBody, 201, oldReply)))
        .as("the old path").singleElement().asString().contains("no such operation");
    URI rightPath = URI.create("http://h/csh/v1/hotels/DEMO1/reservations/RES-1/payments");
    assertThat(OHIP.check(new Exchange("POST", rightPath, headers, oldBody, 201, oldReply)))
        .as("the old body and reply on the right path").anyMatch(p -> p.contains("paymentCode")).anyMatch(p -> p.contains("transactionNo"));
    HttpHeaders noAppKey = new HttpHeaders();
    noAppKey.setBearerAuth("t");
    noAppKey.add("x-hotelid", "DEMO1");
    String oldDate = "{\"businessDate\":\"2026-11-18\"}";
    assertThat(OHIP.check(new Exchange("GET", URI.create("http://h/bof/v1/hotels/DEMO1/businessDate?when=today"), noAppKey, "", 200, oldDate)))
        .anyMatch(p -> p.contains("x-app-key")).anyMatch(p -> p.contains("when")).anyMatch(p -> p.contains("businessDate"));
    // keeps the after-each check meaningful
    exchanges.add(new Exchange("GET", URI.create(opera.url() + "/bof/v1/hotels/DEMO1/businessDate"), conforming(), "", 503, ""));
  }

  private static HttpHeaders conforming() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("t");
    headers.add("x-app-key", "k");
    headers.add("x-hotelid", "DEMO1");
    return headers;
  }

  /** The reply, already read for the check, handed back to the adapter unchanged. */
  private record Replayed(ClientHttpResponse original, byte[] body) implements ClientHttpResponse {
    @Override
    public HttpStatusCode getStatusCode() throws IOException { return original.getStatusCode(); }

    @Override
    public String getStatusText() throws IOException { return original.getStatusText(); }

    @Override
    public HttpHeaders getHeaders() { return original.getHeaders(); }

    @Override
    public InputStream getBody() { return new ByteArrayInputStream(body); }

    @Override
    public void close() { original.close(); }
  }
}
