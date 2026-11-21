package dev.foliopay.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.SettlementLine;
import dev.foliopay.gateway.OperaClient.OperaPayment;
import dev.foliopay.gateway.OperaClient.Posted;
import dev.foliopay.gateway.OperaRejectedException;
import dev.foliopay.gateway.OperaUnavailableException;
import dev.foliopay.gateway.PspDeclinedException;
import dev.foliopay.gateway.PspRejectedException;
import dev.foliopay.gateway.PspTransientException;
import dev.foliopay.gateway.PspUnreadableReplyException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/**
 * The replies the simulators never send: a rate limit from the processor, an OPERA answer without a transaction number, a date or payment
 * we cannot read, and many requests refused with 401 at once. A tiny local HTTP server (the JDK's own) plays the other side.
 */
@DisplayName("HTTP adapters: the replies the simulators never send")
class HttpAdapterRepliesTest {
  private static final Money TEN = new Money(1_000, "USD");
  private HttpServer server;
  private String url;
  private final Map<String, Reply> replies = new ConcurrentHashMap<>();
  private final List<String> requested = new CopyOnWriteArrayList<>();
  private final AtomicInteger tokensIssued = new AtomicInteger();

  /** A reply for a path, or for a path with its exact query (which wins), so each page of a folio can have its own. */
  record Reply(int status, String body) {}

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newFixedThreadPool(32));
    server.createContext("/", this::answer);
    server.start();
    url = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() { server.stop(0); }

  private void answer(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getRawQuery();
    String asked = query == null ? path : path + "?" + query;
    requested.add(asked);
    Reply reply = replies.containsKey(asked) ? replies.get(asked)
        : replies.containsKey(path) ? replies.get(path)
        : path.equals("/oauth/v1/tokens") ? new Reply(200, "{\"access_token\":\"t" + tokensIssued.incrementAndGet() + "\",\"expires_in\":3600}")
        : new Reply(404, "{}");
    byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(reply.status(), body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private HttpGatewayProperties settings() {
    return new HttpGatewayProperties(url, "app", "client", "secret", "ENT", "scope", url, "psp-key", Duration.ofSeconds(2), Duration.ofSeconds(3));
  }

  private HttpPspClient psp() { return new HttpPspClient(settings(), RestClient.builder()); }

  private HttpOperaClient opera() { return new HttpOperaClient(settings(), RestClient.builder(), Clock.systemUTC()); }

  private static OperaPayment payment() {
    return new OperaPayment("H1", "RES-1", 1, "VCC", 7001, TEN, "post:c1", "cap_1");
  }

  private static final String OPERA_PAYMENTS = "/csh/v1/hotels/H1/reservations/RES-1/payments";
  private static final String OPERA_FOLIO = "/csh/v1/hotels/H1/reservations/RES-1/folios";
  private static final String OPERA_DATE = "/bof/v1/hotels/H1/businessDate";

  /** A folio holding one Payment posting with our reference, as getFolio returns it; {@code posting} adds the posting's other fields. */
  private static String folio(String posting) {
    return "{\"reservationFolioInformation\":{\"folioWindows\":[{\"folioWindowNo\":1,\"folios\":[{\"postings\":["
        + "{\"transactionType\":\"Payment\",\"reference\":\"post:c1\"," + posting + "}]}]}]}}";
  }

  /**
   * Fails if OPERA refusing our credentials dead-letters the posting as a rejection: a rejection is never replayed, so every payment taken
   * while a rotated secret is being fixed would need a database repair, though each folio is fine (review finding, the twin of D-34).
   */
  @ParameterizedTest(name = "token endpoint HTTP {0}")
  @ValueSource(ints = {400, 401})
  @DisplayName("OPERA refusing our credentials is replayable, not a rejection of the folio")
  void operaRefusingOurCredentialsIsReplayableNotARejectionOfTheFolio(int status) {
    replies.put("/oauth/v1/tokens", new Reply(status, "{\"error\":\"invalid_client\"}"));
    assertThatThrownBy(() -> opera().postPayment(payment()))
        .isInstanceOf(OperaUnavailableException.class).isNotInstanceOf(OperaRejectedException.class).hasMessageContaining("credentials")
        .as("the stored reason names every setting the token needs").hasMessageContaining("enterprise id and scope");
  }

  /** Split from the test above in phase 21: the same promise for a token reply without a token, and for an application OPERA refuses. */
  @Test
  @DisplayName("OPERA answering without a token, or refusing the application, is replayable too")
  void operaAnsweringWithoutATokenOrRefusingTheApplicationIsReplayableToo() {
    replies.put("/oauth/v1/tokens", new Reply(200, "{\"expires_in\":3600}"));
    assertThatThrownBy(() -> opera().postPayment(payment())).as("a token reply without a token").isInstanceOf(OperaUnavailableException.class)
        .hasMessageContaining("without a token");
    replies.remove("/oauth/v1/tokens");
    replies.put(OPERA_PAYMENTS, new Reply(403, "{\"code\":\"APPLICATION_NOT_ALLOWED\"}"));
    assertThatThrownBy(() -> opera().postPayment(payment())).isInstanceOf(OperaUnavailableException.class).hasMessageContaining("credentials");
  }

  /**
   * Fails if the client id and secret are not sent as issued, in UTF-8. Spring's default for Basic credentials is ISO-8859-1: a secret
   * with a character outside it (a euro sign) used to throw before the request left, an unchecked error rather than "unavailable". Oracle
   * asks for the Basic Access Authorization standard (RFC 7617), whose charset is UTF-8, and describes the header as ClientID:ClientSecret,
   * so nothing is URL-encoded (the review suggested that too; Oracle's own description of the header says otherwise).
   */
  @Test
  @DisplayName("The client id and secret reach OHIP as Basic credentials in UTF-8, exactly as issued")
  void theClientIdAndSecretReachOhipAsBasicCredentialsInUtf8ExactlyAsIssued() {
    List<String> authorizations = new CopyOnWriteArrayList<>();
    server.removeContext("/");
    server.createContext("/", exchange -> {
      if (exchange.getRequestURI().getPath().equals("/oauth/v1/tokens")) {
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
      }
      answer(exchange);
    });
    replies.put(OPERA_DATE, new Reply(200, "{\"hotels\":[{\"hotelId\":\"H1\",\"businessDate\":\"2026-11-17\"}]}"));
    String secret = "s3cr\u20ac t+/=:%\u00e9";
    HttpGatewayProperties settings = new HttpGatewayProperties(url, "app", "bridge-1", secret, "ENT", "scope", url, "psp-key",
        Duration.ofSeconds(2), Duration.ofSeconds(3));
    new HttpOperaClient(settings, RestClient.builder(), Clock.systemUTC()).businessDate("H1");
    assertThat(authorizations).singleElement().satisfies(header -> assertThat(header).startsWith("Basic "));
    String credentials = new String(Base64.getDecoder().decode(authorizations.get(0).substring("Basic ".length())), StandardCharsets.UTF_8);
    assertThat(credentials).isEqualTo("bridge-1:" + secret);
  }

  /** Fails if a wrong address or a proxy's 404 (no error code of theirs) declines a card or rejects a folio for good. */
  @Test
  @DisplayName("A 404 without the system's own error code is a wrong address, not a refusal")
  void a404WithoutTheirOwnErrorCodeIsAWrongAddressNotARefusal() {
    replies.put(OPERA_PAYMENTS, new Reply(404, "<html>Not Found</html>"));
    assertThatThrownBy(() -> opera().postPayment(payment())).isInstanceOf(OperaUnavailableException.class);
    replies.put(OPERA_PAYMENTS, new Reply(404, "{\"code\":\"RESERVATION_NOT_FOUND\"}"));
    assertThatThrownBy(() -> opera().postPayment(payment())).as("OPERA's own answer about the reservation")
        .isInstanceOf(OperaRejectedException.class).hasMessageContaining("RESERVATION_NOT_FOUND");

    replies.put("/v1/captures", new Reply(404, "<html>Not Found</html>"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).isInstanceOf(PspTransientException.class);
    replies.put("/v1/captures", new Reply(408, ""));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).as("request timeout").isInstanceOf(PspTransientException.class);
    replies.put("/v1/captures", new Reply(404, "{\"code\":\"UNKNOWN_AUTHORIZATION\"}"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).as("the processor's own answer").isInstanceOf(PspRejectedException.class);
  }

  /** Fails if a success without a reference becomes a blank reference: a capture of "" declines the card and a void of "" keeps the hold. */
  @Test
  @DisplayName("A processor success without a reference is unreadable, never a blank reference")
  void aProcessorSuccessWithoutAReferenceIsUnreadableNeverABlankReference() {
    replies.put("/v1/authorizations", new Reply(201, "{\"id\":\"auth_1\"}"));
    assertThatThrownBy(() -> psp().authorize("k:auth", new Pan("4111111111111111"), "12/29", TEN)).isInstanceOf(PspUnreadableReplyException.class);
    replies.put("/v1/captures", new Reply(200, "{}"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).isInstanceOf(PspUnreadableReplyException.class);
  }

  /**
   * Fails if a statement line without its reference, a whole amount or a currency becomes a line with a blank reference or zero:
   * reconciliation would report every capture as an exception instead of saying the statement could not be read (standards section 5,
   * API10; final code review). A statement with no lines is valid: nothing was settled.
   */
  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {
      "{\"lines\":[{\"ref\":\"cap_1\",\"amount\":1000,\"currency\":\"USD\"}]}",
      "{\"lines\":[{\"pspRef\":\"cap_1\",\"amountMinor\":10.5,\"currency\":\"USD\"}]}",
      "{\"lines\":[{\"pspRef\":\"cap_1\",\"amountMinor\":1000}]}",
      "{\"statement\":[]}",
      ""})
  @DisplayName("A settlement statement that cannot be read is unreadable, never lines with blank references or zero amounts")
  void aSettlementStatementThatCannotBeReadIsUnreadableNeverLinesWithBlankReferencesOrZeroAmounts(String unreadable) {
    replies.put("/v1/settlements", new Reply(200, unreadable));
    assertThatThrownBy(() -> psp().settlementStatement()).isInstanceOf(PspUnreadableReplyException.class);
  }

  /** Split from the test above in phase 21: the two replies that are read as they are. */
  @Test
  @DisplayName("An empty statement is nothing settled, and a readable line is read exactly")
  void anEmptyStatementIsNothingSettledAndAReadableLineIsReadExactly() {
    replies.put("/v1/settlements", new Reply(200, "{\"lines\":[]}"));
    assertThat(psp().settlementStatement()).as("nothing settled").isEmpty();
    replies.put("/v1/settlements", new Reply(200, "{\"lines\":[{\"pspRef\":\"cap_1\",\"amountMinor\":1000,\"currency\":\"USD\"}]}"));
    assertThat(psp().settlementStatement()).containsExactly(new SettlementLine("cap_1", 1_000, "USD"));
  }

  @ParameterizedTest(name = "HTTP {0}")
  @ValueSource(ints = {429, 409, 503})
  @DisplayName("A processor rate limit, or a parallel request with the same key, is waited out, not treated as our bug")
  void aProcessorRateLimitOrAParallelRequestWithTheSameKeyIsWaitedOutNotTreatedAsOurBug(int status) {
    replies.put("/v1/captures", new Reply(status, "{\"code\":\"REQUEST_IN_PROGRESS\"}"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).isInstanceOf(PspTransientException.class);
  }

  @Test
  @DisplayName("A decline is final; any other 4xx is ours to fix and is not retried")
  void aDeclineIsFinalAndAnyOther4xxIsOursAndNotRetried() {
    replies.put("/v1/authorizations", new Reply(402, "{}"));
    assertThatThrownBy(() -> psp().authorize("k:auth", new Pan("4111111111111111"), "12/29", TEN)).isInstanceOf(PspDeclinedException.class)
        .isNotInstanceOf(PspRejectedException.class);
    replies.put("/v1/authorizations", new Reply(400, "{}"));
    assertThatThrownBy(() -> psp().authorize("k:auth", new Pan("4111111111111111"), "12/29", TEN))
        .isInstanceOf(PspRejectedException.class).isNotInstanceOf(PspTransientException.class).hasMessageNotContaining("4111");
  }

  /** Fails if a capture refused as not matching its authorisation escapes as an unchecked error: the hold would never be released. */
  @Test
  @DisplayName("A capture the processor refuses is a rejection with its code, so the charge can release the hold")
  void aCaptureTheProcessorRefusesIsARejectionWithItsCodeSoTheChargeCanReleaseTheHold() {
    replies.put("/v1/captures", new Reply(422, "{\"code\":\"AMOUNT_MISMATCH\",\"message\":\"no\"}"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).isInstanceOf(PspRejectedException.class).hasMessageContaining("AMOUNT_MISMATCH");
    replies.put("/v1/captures", new Reply(404, "{\"code\":\"UNKNOWN_AUTHORIZATION\"}"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).isInstanceOf(PspRejectedException.class)
        .hasMessageContaining("UNKNOWN_AUTHORIZATION");
  }

  /** Fails if every 409 is retried: a key reused with a different request would keep a charge, and a payment link, pending for ever. */
  @Test
  @DisplayName("A key reused with a different request is final, not waited out")
  void aKeyReusedWithADifferentRequestIsFinalNotWaitedOut() {
    replies.put("/v1/captures", new Reply(409, "{\"code\":\"IDEMPOTENCY_KEY_REUSED\"}"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).isInstanceOf(PspRejectedException.class)
        .isNotInstanceOf(PspTransientException.class);
  }

  /**
   * Fails if an error code read from outside reaches our message unredacted: the message is stored on the charge, returned by the API and
   * logged, so a processor or OPERA that echoes a card number, a card token or a credential would spread it to all three.
   */
  @Test
  @DisplayName("A third-party error that echoes a card number, a card token or a credential is redacted before it becomes our message")
  void aThirdPartyErrorThatEchoesSecretsIsRedactedBeforeItBecomesOurMessage() {
    String token = "tok_" + "0123456789abcdef".repeat(2);
    replies.put("/v1/captures", new Reply(422, "{\"code\":\"BAD_CARD 4111111111111111 " + token + "\"}"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).isInstanceOf(PspRejectedException.class)
        .hasMessageContaining("BAD_CARD").hasMessageNotContaining("4111111111111111").hasMessageNotContaining(token);
    replies.put(OPERA_PAYMENTS, new Reply(400, "{\"o:errorCode\":\"INVALID password=hunter22\"}"));
    assertThatThrownBy(() -> opera().postPayment(payment())).isInstanceOf(OperaRejectedException.class)
        .hasMessageContaining("INVALID").hasMessageNotContaining("hunter22");
  }

  /** Fails if the reason is cut out of the body by hand: a truncated body used to throw StringIndexOutOfBounds instead of a rejection. */
  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"{\"code\":\"FOLIO_CLOSED", "<html>Bad gateway</html>", ""})
  @DisplayName("A truncated or non-JSON rejection from OPERA is still a rejection, not a crash")
  void aTruncatedOrNonJsonRejectionFromOperaIsStillARejectionNotACrash(String body) {
    replies.put(OPERA_PAYMENTS, new Reply(400, body));
    assertThatThrownBy(() -> opera().postPayment(payment())).isInstanceOf(OperaRejectedException.class).hasMessageContaining("no reason given");
  }

  /** Split from the test above in phase 21: a readable problem detail keeps OPERA's own error code. */
  @Test
  @DisplayName("A problem-detail rejection from OPERA keeps its error code")
  void aProblemDetailRejectionFromOperaKeepsItsErrorCode() {
    replies.put(OPERA_PAYMENTS, new Reply(400, "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"o:errorCode\":\"FOLIO_CLOSED\"}"));
    assertThatThrownBy(() -> opera().postPayment(payment())).isInstanceOf(OperaRejectedException.class).hasMessageContaining("FOLIO_CLOSED");
  }

  /** Fails if a payment OPERA lists without a transaction number is returned as found: it would be recorded POSTED with a blank reference. */
  @Test
  @DisplayName("A found payment without a transaction number is neither posted again nor recorded as posted")
  void aFoundPaymentWithoutATransactionNumberIsNeitherPostedAgainNorRecordedAsPosted() {
    replies.put(OPERA_FOLIO, new Reply(200,
        folio("\"transactionNo\":\"\",\"postedAmount\":{\"amount\":10.00,\"currencyCode\":\"USD\"},\"transactionDate\":\"2026-11-17\"")));
    assertThatThrownBy(() -> opera().find("H1", "RES-1", "post:c1"))
        .isInstanceOf(OperaUnavailableException.class).hasMessageContaining("transaction number");
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"{}", "", // OHIP may answer 201 with only a Location
      "{\"postings\":[{\"transactionType\":\"Revenue\",\"transactionNo\":1001}]}", // a generated charge (a surcharge), not our payment
      "{\"postings\":[{\"transactionType\":\"Payment\",\"transactionNo\":\"\"}]}"})
  @DisplayName("An OPERA reply without a transaction number is not recorded as posted with a blank reference")
  void anOperaReplyWithoutATransactionNumberIsNotRecordedAsPostedWithABlankReference(String reply) {
    replies.put(OPERA_PAYMENTS, new Reply(201, reply));
    assertThatThrownBy(() -> opera().postPayment(payment())).isInstanceOf(OperaUnavailableException.class).hasMessageContaining("transaction number");
  }

  /** Split from the test above in phase 21: our payment is told apart from the postings OPERA generated with it. */
  @Test
  @DisplayName("Our payment is found among the postings OPERA generated with it")
  void ourPaymentIsFoundAmongThePostingsOperaGeneratedWithIt() {
    replies.put(OPERA_PAYMENTS, new Reply(201,
        "{\"postings\":[{\"transactionType\":\"Revenue\",\"transactionNo\":1001},{\"transactionType\":\"Payment\",\"transactionNo\":1002}]}"));
    assertThat(opera().postPayment(payment())).as("the payment among generated postings").isEqualTo("1002");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"{\"hotels\":[{\"hotelId\":\"H1\",\"businessDate\":\"17/11/2026\"}]}",
      "{\"hotels\":[{\"hotelId\":\"OTHER\",\"businessDate\":\"2026-11-17\"}]}", "{}", "not json"})
  @DisplayName("A business date we cannot read is an outage, not a crash")
  void aBusinessDateWeCannotReadIsAnOutageNotACrash(String bad) {
    replies.put(OPERA_DATE, new Reply(200, bad));
    assertThatThrownBy(() -> opera().businessDate("H1")).isInstanceOf(OperaUnavailableException.class);
  }

  /**
   * Fails if a field the bridge does not need decides whether our payment is there. Oracle's {@code currencyAmountType} makes the currency
   * optional; a payment OPERA lists without one used to be "unavailable", so a post whose reply was lost was dead-lettered on every replay
   * and never recorded as posted (OHIP review). Our reference and OPERA's transaction number identify it; amount and date are read if they
   * can be.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("foliosWithAPartWeCannotRead")
  @DisplayName("Our payment on the folio is found by its transaction number even when its amount or date cannot be read")
  void aFoundPaymentIsFoundByItsTransactionNumberEvenWhenItsAmountOrDateCannotBeRead(String what, String listed, Posted posted) {
    replies.put(OPERA_FOLIO, new Reply(200, listed));
    assertThat(opera().find("H1", "RES-1", "post:c1")).contains(posted);
  }

  static Stream<Arguments> foliosWithAPartWeCannotRead() {
    LocalDate day = LocalDate.of(2026, 11, 17);
    Posted dateOnly = new Posted("1001", "RES-1", Optional.empty(), Optional.of(day));
    return Stream.of(
        Arguments.of("no currency", folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.00},\"transactionDate\":\"2026-11-17\""), dateOnly),
        Arguments.of("an odd currency",
            folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.00,\"currencyCode\":\"ZZ\"},\"transactionDate\":\"2026-11-17\""), dateOnly),
        Arguments.of("a fraction of a cent",
            folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.001,\"currencyCode\":\"USD\"},\"transactionDate\":\"2026-11-17\""),
            dateOnly),
        Arguments.of("a date that cannot be read",
            folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.00,\"currencyCode\":\"USD\"},\"transactionDate\":\"soon\""),
            new Posted("1001", "RES-1", Optional.of(TEN), Optional.empty())));
  }

  /** Review finding L4: every request that got a 401 used to fetch its own new token, one after another under the lock. */
  @Test
  @DisplayName("Many requests refused with the same expired token cause one new token, not one each")
  void manyRequestsRefusedWithTheSameExpiredTokenCauseOneNewTokenNotOneEach() throws Exception {
    int callers = 12;
    CountDownLatch allRefused = new CountDownLatch(callers);
    server.removeContext("/");
    server.createContext("/", exchange -> {
      String bearer = exchange.getRequestHeaders().getFirst("Authorization");
      if (exchange.getRequestURI().getPath().equals(OPERA_DATE) && "Bearer t1".equals(bearer)) {
        allRefused.countDown();
        try {
          allRefused.await(5, TimeUnit.SECONDS); // every caller holds the old token at the same moment
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        replies.put(OPERA_DATE, new Reply(200, "{\"hotels\":[{\"hotelId\":\"H1\",\"businessDate\":\"2026-11-17\"}]}"));
        exchange.sendResponseHeaders(401, -1);
        exchange.close();
        return;
      }
      answer(exchange);
    });
    HttpOperaClient client = opera();
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    List<Future<LocalDate>> results = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      results.add(pool.submit(() -> client.businessDate("H1")));
    }
    for (Future<LocalDate> result : results) {
      assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(LocalDate.of(2026, 11, 17));
    }
    pool.shutdown();

    assertThat(tokensIssued.get()).as("the first token, and one replacement").isEqualTo(2);
  }

  /** Review finding L2: HTTP mode used to default to localhost and empty credentials. */
  @Test
  @DisplayName("HTTP mode refuses to start without its addresses and credentials")
  void httpModeRefusesToStartWithoutItsAddressesAndCredentials() {
    HttpGatewayProperties none =
        new HttpGatewayProperties(null, null, null, null, null, null, null, null, Duration.ofSeconds(2), Duration.ofSeconds(3));
    assertThatThrownBy(() -> new HttpOperaClient(none, RestClient.builder(), Clock.systemUTC())).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opera-base-url");
    assertThatThrownBy(() -> new HttpPspClient(none, RestClient.builder())).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("psp-base-url");
    HttpGatewayProperties noSecret =
        new HttpGatewayProperties(url, "app", "client", " ", "ENT", "scope", url, "k", Duration.ofSeconds(2), Duration.ofSeconds(3));
    assertThatThrownBy(() -> new HttpOperaClient(noSecret, RestClient.builder(), Clock.systemUTC())).hasMessageContaining("opera-client-secret");
    HttpGatewayProperties noScope =
        new HttpGatewayProperties(url, "app", "client", "secret", "ENT", "", url, "k", Duration.ofSeconds(2), Duration.ofSeconds(3));
    assertThatThrownBy(() -> new HttpOperaClient(noScope, RestClient.builder(), Clock.systemUTC())).hasMessageContaining("opera-scope");
  }

  /** Fails if a refused credential declines the guest's charge for good: the charge must stay retryable until the key is fixed. */
  @ParameterizedTest(name = "HTTP {0}")
  @ValueSource(ints = {401, 403})
  @DisplayName("Our processor credentials being refused is retryable, not a decline of the guest's card")
  void ourCredentialsBeingRefusedIsRetryableNotADeclineOfTheGuestsCard(int status) {
    replies.put("/v1/authorizations", new Reply(status, "{\"code\":\"BAD_API_KEY\"}"));
    assertThatThrownBy(() -> psp().authorize("k:auth", new Pan("4111111111111111"), "12/29", TEN))
        .isInstanceOf(PspTransientException.class).isNotInstanceOf(PspDeclinedException.class).hasMessageNotContaining("4111");
  }

  /** Fails if a folio OPERA returned only in part is read as "not there": the bridge would post again, and OPERA does not deduplicate. */
  /**
   * Fails if a reply that is not a folio reads as "our payment is not there": the bridge would post again, and OPERA does not deduplicate.
   * A renamed or wrapped reply, or an empty 200, proves nothing (final code review). A real folio that has no windows yet, and Oracle's 204
   * "Folio not found", still prove the payment absent, or the first payment of a stay could never post (agent log 54: a fail-safe can be
   * too strict as well as too lax).
   */
  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"{\"folioInformation\":{\"folioWindows\":[]}}", "{}", ""})
  @DisplayName("A reply that is not a folio is unavailable: it cannot prove the payment absent")
  void aReplyThatIsNotAFolioIsUnavailableButAFolioWithNoWindowsProvesThePaymentAbsent(String notAFolio) {
    replies.put(OPERA_FOLIO, new Reply(200, notAFolio));
    assertThatThrownBy(() -> opera().find("H1", "RES-1", "post:c1")).isInstanceOf(OperaUnavailableException.class);
  }

  /** Split from the test above in phase 21: OPERA's own empty folio is the one reply that proves the payment is not there. */
  @Test
  @DisplayName("A folio with no windows yet proves the payment absent")
  void aFolioWithNoWindowsYetProvesThePaymentAbsent() {
    replies.put(OPERA_FOLIO, new Reply(200, "{\"reservationFolioInformation\":{}}"));
    assertThat(opera().find("H1", "RES-1", "post:c1")).as("a folio with no windows yet").isEmpty();
  }

  @Test
  @DisplayName("A folio returned only in part is unavailable, not absent")
  void aFolioReturnedOnlyInPartIsUnavailableNotAbsent() {
    replies.put(OPERA_FOLIO, new Reply(200, windows(window("\"folioWindowNo\":1,\"hasMore\":true"))));
    assertThatThrownBy(() -> opera().find("H1", "RES-1", "post:c1"))
        .isInstanceOf(OperaUnavailableException.class).hasMessageContaining("part of the folio");
    replies.put(OPERA_FOLIO, new Reply(200, windows(window("\"folioWindowNo\":1,\"hasMore\":false"))));
    assertThat(opera().find("H1", "RES-1", "post:c1")).isEmpty();
  }

  private static final String FIRST_PAGE = OPERA_FOLIO + "?fetchInstructions=Postings";

  /** The request for one window's next page: Oracle's {@code folioWindowNo} and {@code offset}, the offset counted in postings. */
  private static String nextPage(int window, int offset) {
    return OPERA_FOLIO + "?fetchInstructions=Postings&folioWindowNo=" + window + "&offset=" + offset;
  }

  private static String windows(String... windows) {
    return "{\"reservationFolioInformation\":{\"folioWindows\":[" + String.join(",", windows) + "]}}";
  }

  /** A folio window: {@code fields} are its number and paging fields, then its postings in one folio. */
  private static String window(String fields, String... postings) {
    return "{" + fields + ",\"folios\":[{\"postings\":[" + String.join(",", postings) + "]}]}";
  }

  private static String charge(int transactionNo) {
    return "{\"transactionType\":\"Revenue\",\"transactionNo\":" + transactionNo + ",\"reference\":\"room night\"}";
  }

  private static String ourPayment(int transactionNo) {
    return "{\"transactionType\":\"Payment\",\"reference\":\"post:c1\",\"transactionNo\":" + transactionNo
        + ",\"postedAmount\":{\"amount\":10.00,\"currencyCode\":\"USD\"},\"transactionDate\":\"2026-11-17\"}";
  }

  /**
   * Fails if a window longer than one page stops its guest being paid. The bridge looks before every post, the first one too, and a window
   * that said {@code hasMore} used to end every look as "unavailable": the payment was dead-lettered and every replay hit the same page
   * (OHIP review). Its next page is asked for by window number and offset, Oracle's paging parameters, until the last.
   */
  @Test
  @DisplayName("A long folio is read page by page, so its guest can be paid")
  void aLongFolioIsReadPageByPageSoItsGuestCanBePaid() {
    replies.put(FIRST_PAGE, new Reply(200, windows(window("\"folioWindowNo\":1,\"hasMore\":true", charge(1001), charge(1002)),
        window("\"folioWindowNo\":2,\"hasMore\":false", charge(1003)))));
    replies.put(nextPage(1, 2), new Reply(200, windows(window("\"folioWindowNo\":1,\"hasMore\":true,\"offset\":2", charge(1004), charge(1005)))));
    replies.put(nextPage(1, 4),
        new Reply(200, windows(window("\"folioWindowNo\":1,\"hasMore\":false,\"offset\":4,\"totalResults\":5", charge(1006)))));
    assertThat(opera().find("H1", "RES-1", "post:c1")).as("read to the end and not there: the payment may be posted").isEmpty();
    assertThat(requested).contains(nextPage(1, 2), nextPage(1, 4)).noneMatch(r -> r.contains("folioWindowNo=2"));

    replies.put(nextPage(1, 4), new Reply(200, windows(window("\"folioWindowNo\":1,\"hasMore\":false", charge(1006), ourPayment(1007)))));
    assertThat(opera().find("H1", "RES-1", "post:c1")).as("on the last page").get().extracting(Posted::operaRef).isEqualTo("1007");
  }

  /**
   * Fails if a page that cannot prove our payment absent is read as "not there": the bridge would post again, and OPERA does not
   * deduplicate. Each is what a misreading of Oracle's paging would look like (an offset counted in pages, not postings, lands past the end
   * and returns nothing, or skips pages), or a reply that breaks its own promise of more.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("secondPagesThatCannotProveAbsence")
  @DisplayName("A folio page that cannot prove our payment absent is unavailable, never \"not found\"")
  void aFolioPageThatCannotProveOurPaymentAbsentIsUnavailableNeverNotFound(String what, Reply secondPage) {
    replies.put(FIRST_PAGE, new Reply(200, windows(window("\"folioWindowNo\":1,\"hasMore\":true", charge(1001), charge(1002)))));
    replies.put(nextPage(1, 2), secondPage);
    assertThatThrownBy(() -> opera().find("H1", "RES-1", "post:c1"))
        .isInstanceOf(OperaUnavailableException.class).hasMessageContaining("part of the folio");
  }

  static Stream<Arguments> secondPagesThatCannotProveAbsence() {
    return Stream.of(
        Arguments.of("an empty page after a promise of more", new Reply(200, windows(window("\"folioWindowNo\":1,\"hasMore\":false")))),
        Arguments.of("no folio at all", new Reply(204, "")),
        Arguments.of("another window than the one asked for", new Reply(200, windows(window("\"folioWindowNo\":2,\"hasMore\":false", charge(1003))))),
        Arguments.of("fewer postings read than the window's total",
            new Reply(200, windows(window("\"folioWindowNo\":1,\"hasMore\":false,\"totalResults\":40", charge(1003))))));
  }

  /** Split from the test above in phase 21: a first page that cannot be continued proves nothing either. */
  @Test
  @DisplayName("A window without its number cannot be asked for its next page, so it is unavailable")
  void aWindowWithoutItsNumberCannotBeAskedForItsNextPage() {
    replies.put(FIRST_PAGE, new Reply(200, windows(window("\"hasMore\":true", charge(1001)))));
    assertThatThrownBy(() -> opera().find("H1", "RES-1", "post:c1"))
        .isInstanceOf(OperaUnavailableException.class).hasMessageContaining("part of the folio");
  }

  /** Split from the test above in phase 21: fails if a window that always says "more" is read for ever. */
  @Test
  @DisplayName("A window that never ends is read for at most fifty pages, then it is unavailable")
  void aWindowThatNeverEndsIsReadForAtMostFiftyPages() {
    replies.put(OPERA_FOLIO, new Reply(200, windows(window("\"folioWindowNo\":1,\"hasMore\":true", charge(1001))))); // every page says there is more
    assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThatThrownBy(() -> opera().find("H1", "RES-1", "post:c1"))
        .isInstanceOf(OperaUnavailableException.class).hasMessageContaining("part of the folio"));
    assertThat(requested.stream().filter(r -> r.startsWith(OPERA_FOLIO))).as("pages asked for, bounded").hasSize(50);
  }

  /** OPERA may list a payment as a credit (a negative amount); a charge carrying our reference is not our payment. */
  @Test
  @DisplayName("A payment listed as a credit is found; a charge carrying our reference is not")
  void aPaymentListedAsACreditIsFoundAndAChargeWithOurReferenceIsNot() {
    replies.put(OPERA_FOLIO, new Reply(200,
        folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":-10.00,\"currencyCode\":\"USD\"},\"transactionDate\":\"2026-11-17\"")));
    assertThat(opera().find("H1", "RES-1", "post:c1").orElseThrow().amount()).contains(TEN);
    replies.put(OPERA_FOLIO, new Reply(200,
        folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.000,\"currencyCode\":\"USD\"},\"postingDate\":\"2026-11-17\"")));
    assertThat(opera().find("H1", "RES-1", "post:c1").orElseThrow()).as("extra zero decimals, and the posting date when there is no transaction date")
        .satisfies(p -> assertThat(p.amount()).contains(TEN)).satisfies(p -> assertThat(p.businessDate()).contains(LocalDate.of(2026, 11, 17)));
    String charge = folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.00,\"currencyCode\":\"USD\"},\"transactionDate\":\"2026-11-17\"")
        .replace("Payment", "Revenue");
    replies.put(OPERA_FOLIO, new Reply(200, charge));
    assertThat(opera().find("H1", "RES-1", "post:c1")).isEmpty();
  }

  /** A reservation with no folio yet (the guest has not checked in) answers 204: nothing posted, so the payment may be posted. */
  @Test
  @DisplayName("A folio with no content (204) means nothing is posted yet")
  void aFolioWithNoContentIsNotFound() {
    replies.put(OPERA_FOLIO, new Reply(204, ""));
    assertThat(opera().find("H1", "RES-1", "post:c1")).isEmpty();
  }
}
