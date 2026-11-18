package dev.foliopay.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.gateway.OperaClient.OperaPayment;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The replies the simulators never send: a rate limit from the processor, an OPERA answer without a transaction number, a date or payment
 * we cannot read, and many requests refused with 401 at once. A tiny local HTTP server (the JDK's own) plays the other side.
 */
class HttpAdapterRepliesTest {
  private static final Money TEN = new Money(1_000, "USD");
  private HttpServer server;
  private String url;
  private final Map<String, Reply> replies = new ConcurrentHashMap<>();
  private final AtomicInteger tokensIssued = new AtomicInteger();

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
    Reply reply = replies.containsKey(path) ? replies.get(path)
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
    return "{\"reservationFolioInformation\":{\"folioWindows\":[{\"folioWindowNo\":1,\"folios\":[{\"postings\":[{\"transactionType\":\"Payment\",\"reference\":\"post:c1\","
        + posting + "}]}]}]}}";
  }

  /**
   * Fails if OPERA refusing our credentials dead-letters the posting as a rejection: a rejection is never replayed, so every payment taken
   * while a rotated secret is being fixed would need a database repair, though each folio is fine (review finding, the twin of D-34).
   */
  @Test
  void operaRefusingOurCredentialsIsReplayableNotARejectionOfTheFolio() {
    for (int status : new int[] {400, 401}) {
      replies.put("/oauth/v1/tokens", new Reply(status, "{\"error\":\"invalid_client\"}"));
      assertThatThrownBy(() -> opera().postPayment(payment())).as("token endpoint HTTP %d", status)
          .isInstanceOf(OperaUnavailableException.class).isNotInstanceOf(OperaRejectedException.class).hasMessageContaining("credentials");
    }
    replies.put("/oauth/v1/tokens", new Reply(200, "{\"expires_in\":3600}"));
    assertThatThrownBy(() -> opera().postPayment(payment())).as("a token reply without a token").isInstanceOf(OperaUnavailableException.class)
        .hasMessageContaining("without a token");
    replies.remove("/oauth/v1/tokens");
    replies.put(OPERA_PAYMENTS, new Reply(403, "{\"code\":\"APPLICATION_NOT_ALLOWED\"}"));
    assertThatThrownBy(() -> opera().postPayment(payment())).isInstanceOf(OperaUnavailableException.class).hasMessageContaining("credentials");
  }

  /** Fails if a wrong address or a proxy's 404 (no error code of theirs) declines a card or rejects a folio for good. */
  @Test
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
  void aProcessorSuccessWithoutAReferenceIsUnreadableNeverABlankReference() {
    replies.put("/v1/authorizations", new Reply(201, "{\"id\":\"auth_1\"}"));
    assertThatThrownBy(() -> psp().authorize("k:auth", new Pan("4111111111111111"), "12/29", TEN)).isInstanceOf(PspUnreadableReplyException.class);
    replies.put("/v1/captures", new Reply(200, "{}"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).isInstanceOf(PspUnreadableReplyException.class);
  }

  @Test
  void aProcessorRateLimitOrAParallelRequestWithTheSameKeyIsWaitedOutNotTreatedAsOurBug() {
    for (int status : new int[] {429, 409, 503}) {
      replies.put("/v1/captures", new Reply(status, "{\"code\":\"REQUEST_IN_PROGRESS\"}"));
      assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).as("HTTP %d", status).isInstanceOf(PspTransientException.class);
    }
  }

  @Test
  void aDeclineIsFinalAndAnyOther4xxIsOursAndNotRetried() {
    replies.put("/v1/authorizations", new Reply(402, "{}"));
    assertThatThrownBy(() -> psp().authorize("k:auth", new Pan("4111111111111111"), "12/29", TEN)).isInstanceOf(PspDeclinedException.class).isNotInstanceOf(PspRejectedException.class);
    replies.put("/v1/authorizations", new Reply(400, "{}"));
    assertThatThrownBy(() -> psp().authorize("k:auth", new Pan("4111111111111111"), "12/29", TEN))
        .isInstanceOf(PspRejectedException.class).isNotInstanceOf(PspTransientException.class).hasMessageNotContaining("4111");
  }

  /** Fails if a capture refused as not matching its authorisation escapes as an unchecked error: the hold would never be released. */
  @Test
  void aCaptureTheProcessorRefusesIsARejectionWithItsCodeSoTheChargeCanReleaseTheHold() {
    replies.put("/v1/captures", new Reply(422, "{\"code\":\"AMOUNT_MISMATCH\",\"message\":\"no\"}"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).isInstanceOf(PspRejectedException.class).hasMessageContaining("AMOUNT_MISMATCH");
    replies.put("/v1/captures", new Reply(404, "{\"code\":\"UNKNOWN_AUTHORIZATION\"}"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).isInstanceOf(PspRejectedException.class).hasMessageContaining("UNKNOWN_AUTHORIZATION");
  }

  /** Fails if every 409 is retried: a key reused with a different request would keep a charge, and a payment link, pending for ever. */
  @Test
  void aKeyReusedWithADifferentRequestIsFinalNotWaitedOut() {
    replies.put("/v1/captures", new Reply(409, "{\"code\":\"IDEMPOTENCY_KEY_REUSED\"}"));
    assertThatThrownBy(() -> psp().capture("k:cap", "auth_1", TEN)).isInstanceOf(PspRejectedException.class).isNotInstanceOf(PspTransientException.class);
  }

  /** Fails if the reason is cut out of the body by hand: a truncated body used to throw StringIndexOutOfBounds instead of a rejection. */
  @Test
  void aTruncatedOrNonJsonRejectionFromOperaIsStillARejectionNotACrash() {
    for (String body : List.of("{\"code\":\"FOLIO_CLOSED", "<html>Bad gateway</html>", "")) {
      replies.put(OPERA_PAYMENTS, new Reply(400, body));
      assertThatThrownBy(() -> opera().postPayment(payment())).as(body).isInstanceOf(OperaRejectedException.class).hasMessageContaining("no reason given");
    }
    replies.put(OPERA_PAYMENTS, new Reply(400, "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"o:errorCode\":\"FOLIO_CLOSED\"}"));
    assertThatThrownBy(() -> opera().postPayment(payment())).isInstanceOf(OperaRejectedException.class).hasMessageContaining("FOLIO_CLOSED");
  }

  /** Fails if a payment OPERA lists without a transaction number is returned as found: it would be recorded POSTED with a blank reference. */
  @Test
  void aFoundPaymentWithoutATransactionNumberIsNeitherPostedAgainNorRecordedAsPosted() {
    replies.put(OPERA_FOLIO, new Reply(200, folio("\"transactionNo\":\"\",\"postedAmount\":{\"amount\":10.00,\"currencyCode\":\"USD\"},\"transactionDate\":\"2026-11-17\"")));
    assertThatThrownBy(() -> opera().find("H1", "RES-1", "post:c1")).isInstanceOf(OperaUnavailableException.class).hasMessageContaining("transaction number");
  }

  @Test
  void anOperaReplyWithoutATransactionNumberIsNotRecordedAsPostedWithABlankReference() {
    for (String reply : List.of("{}", "", // OHIP may answer 201 with only a Location
        "{\"postings\":[{\"transactionType\":\"Revenue\",\"transactionNo\":1001}]}", // a generated charge (a surcharge), not our payment
        "{\"postings\":[{\"transactionType\":\"Payment\",\"transactionNo\":\"\"}]}")) {
      replies.put(OPERA_PAYMENTS, new Reply(201, reply));
      assertThatThrownBy(() -> opera().postPayment(payment())).as(reply).isInstanceOf(OperaUnavailableException.class).hasMessageContaining("transaction number");
    }
    replies.put(OPERA_PAYMENTS, new Reply(201, "{\"postings\":[{\"transactionType\":\"Revenue\",\"transactionNo\":1001},{\"transactionType\":\"Payment\",\"transactionNo\":1002}]}"));
    assertThat(opera().postPayment(payment())).as("the payment among generated postings").isEqualTo("1002");
  }

  @Test
  void aBusinessDateWeCannotReadIsAnOutageNotACrash() {
    for (String bad : List.of("{\"hotels\":[{\"hotelId\":\"H1\",\"businessDate\":\"17/11/2026\"}]}", "{\"hotels\":[{\"hotelId\":\"OTHER\",\"businessDate\":\"2026-11-17\"}]}", "{}", "not json")) {
      replies.put(OPERA_DATE, new Reply(200, bad));
      assertThatThrownBy(() -> opera().businessDate("H1")).as(bad).isInstanceOf(OperaUnavailableException.class);
    }
  }

  @Test
  void aFoundPaymentWeCannotReadIsAnOutageNotACrash() {
    for (String bad : List.of(
        folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.00},\"transactionDate\":\"2026-11-17\""), // no currency
        folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.00,\"currencyCode\":\"ZZ\"},\"transactionDate\":\"2026-11-17\""), // odd currency
        folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.001,\"currencyCode\":\"USD\"},\"transactionDate\":\"2026-11-17\""), // a fraction of a cent
        folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.00,\"currencyCode\":\"USD\"},\"transactionDate\":\"soon\""))) {
      replies.put(OPERA_FOLIO, new Reply(200, bad));
      assertThatThrownBy(() -> opera().find("H1", "RES-1", "post:c1")).as(bad).isInstanceOf(OperaUnavailableException.class);
    }
  }

  /** Review finding L4: every request that got a 401 used to fetch its own new token, one after another under the lock. */
  @Test
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
    for (int i = 0; i < callers; i++) results.add(pool.submit(() -> client.businessDate("H1")));
    for (Future<LocalDate> result : results) assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(LocalDate.of(2026, 11, 17));
    pool.shutdown();

    assertThat(tokensIssued.get()).as("the first token, and one replacement").isEqualTo(2);
  }

  /** Review finding L2: HTTP mode used to default to localhost and empty credentials. */
  @Test
  void httpModeRefusesToStartWithoutItsAddressesAndCredentials() {
    HttpGatewayProperties none = new HttpGatewayProperties(null, null, null, null, null, null, null, null, Duration.ofSeconds(2), Duration.ofSeconds(3));
    assertThatThrownBy(() -> new HttpOperaClient(none, RestClient.builder(), Clock.systemUTC())).isInstanceOf(IllegalStateException.class).hasMessageContaining("opera-base-url");
    assertThatThrownBy(() -> new HttpPspClient(none, RestClient.builder())).isInstanceOf(IllegalStateException.class).hasMessageContaining("psp-base-url");
    HttpGatewayProperties noSecret = new HttpGatewayProperties(url, "app", "client", " ", "ENT", "scope", url, "k", Duration.ofSeconds(2), Duration.ofSeconds(3));
    assertThatThrownBy(() -> new HttpOperaClient(noSecret, RestClient.builder(), Clock.systemUTC())).hasMessageContaining("opera-client-secret");
    HttpGatewayProperties noScope = new HttpGatewayProperties(url, "app", "client", "secret", "ENT", "", url, "k", Duration.ofSeconds(2), Duration.ofSeconds(3));
    assertThatThrownBy(() -> new HttpOperaClient(noScope, RestClient.builder(), Clock.systemUTC())).hasMessageContaining("opera-scope");
  }

  /** Fails if a refused credential declines the guest's charge for good: the charge must stay retryable until the key is fixed. */
  @Test
  void ourCredentialsBeingRefusedIsRetryableNotADeclineOfTheGuestsCard() {
    for (int status : new int[] {401, 403}) {
      replies.put("/v1/authorizations", new Reply(status, "{\"code\":\"BAD_API_KEY\"}"));
      assertThatThrownBy(() -> psp().authorize("k:auth", new Pan("4111111111111111"), "12/29", TEN)).as("HTTP %d", status)
          .isInstanceOf(PspTransientException.class).isNotInstanceOf(PspDeclinedException.class).hasMessageNotContaining("4111");
    }
  }

  /** Fails if a folio OPERA returned only in part is read as "not there": the bridge would post again, and OPERA does not deduplicate. */
  @Test
  void aFolioReturnedOnlyInPartIsUnavailableNotAbsent() {
    replies.put(OPERA_FOLIO, new Reply(200, "{\"reservationFolioInformation\":{\"folioWindows\":[{\"folioWindowNo\":1,\"hasMore\":true,\"folios\":[{\"postings\":[]}]}]}}"));
    assertThatThrownBy(() -> opera().find("H1", "RES-1", "post:c1")).isInstanceOf(OperaUnavailableException.class).hasMessageContaining("part of the folio");
    replies.put(OPERA_FOLIO, new Reply(200, "{\"reservationFolioInformation\":{\"folioWindows\":[{\"folioWindowNo\":1,\"hasMore\":false,\"folios\":[{\"postings\":[]}]}]}}"));
    assertThat(opera().find("H1", "RES-1", "post:c1")).isEmpty();
  }

  /** OPERA may list a payment as a credit (a negative amount); a charge carrying our reference is not our payment. */
  @Test
  void aPaymentListedAsACreditIsFoundAndAChargeWithOurReferenceIsNot() {
    replies.put(OPERA_FOLIO, new Reply(200, folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":-10.00,\"currencyCode\":\"USD\"},\"transactionDate\":\"2026-11-17\"")));
    assertThat(opera().find("H1", "RES-1", "post:c1").orElseThrow().amount()).isEqualTo(TEN);
    replies.put(OPERA_FOLIO, new Reply(200, folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.000,\"currencyCode\":\"USD\"},\"postingDate\":\"2026-11-17\"")));
    assertThat(opera().find("H1", "RES-1", "post:c1").orElseThrow()).as("extra zero decimals, and the posting date when there is no transaction date")
        .satisfies(p -> assertThat(p.amount()).isEqualTo(TEN)).satisfies(p -> assertThat(p.businessDate()).isEqualTo(LocalDate.of(2026, 11, 17)));
    replies.put(OPERA_FOLIO, new Reply(200, folio("\"transactionNo\":1001,\"postedAmount\":{\"amount\":10.00,\"currencyCode\":\"USD\"},\"transactionDate\":\"2026-11-17\"").replace("Payment", "Revenue")));
    assertThat(opera().find("H1", "RES-1", "post:c1")).isEmpty();
  }

  /** A reservation with no folio yet (the guest has not checked in) answers 204: nothing posted, so the payment may be posted. */
  @Test
  void aFolioWithNoContentIsNotFound() {
    replies.put(OPERA_FOLIO, new Reply(204, ""));
    assertThat(opera().find("H1", "RES-1", "post:c1")).isEmpty();
  }
}
