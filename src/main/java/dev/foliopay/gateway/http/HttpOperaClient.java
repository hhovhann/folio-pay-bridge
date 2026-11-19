package dev.foliopay.gateway.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import dev.foliopay.domain.model.Money;
import dev.foliopay.gateway.OperaClient;
import dev.foliopay.gateway.OperaRejectedException;
import dev.foliopay.gateway.OperaUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OPERA Cloud over OHIP, on the operations and shapes of Oracle's published specification (D-42; checked by {@code OhipContractTest}):
 * OAuth client credentials ({@code getToken}), the business date ({@code getBusinessDate}), a payment posted to a reservation's folio window
 * ({@code postBillingPayment}) and the folio read back to find it ({@code getFolio}). Each failure is mapped to what the posting service needs
 * to decide: a 4xx that will not change on a retry is a rejection (dead-letter, no replay); an outage, a timeout, a rate limit or a lock
 * conflict is "unavailable" (retry, then look before posting again). An expired access token is replaced once, transparently. Wired only
 * when foliopay.gateway=http.
 */
@Component
@ConditionalOnProperty(name = "foliopay.gateway", havingValue = "http")
public class HttpOperaClient implements OperaClient {
  private static final Logger log = LoggerFactory.getLogger(HttpOperaClient.class);
  private static final long EXPIRY_MARGIN_SECONDS = 10;
  /** Decimals are read as exact decimals, never as binary floating point, so an amount comes back to the same minor units it went out as. */
  private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
  private static final String PAYMENT = "Payment";
  private static final String FOLIO = "/csh/v1/hotels/{h}/reservations/{r}/folios";
  private static final String POSTINGS = "Postings";
  /** Pages read per window before giving up as "unavailable": with OPERA's page size, far more postings than any one stay has. */
  private static final int MAX_FOLIO_PAGES = 50;
  private final RestClient http;
  private final HttpGatewayProperties settings;
  private final Clock clock;
  private String accessToken;
  private Instant tokenExpiresAt = Instant.EPOCH;

  HttpOperaClient(HttpGatewayProperties settings, RestClient.Builder builder, Clock clock) {
    settings.requireOpera();
    this.settings = settings;
    this.clock = clock;
    this.http = builder.baseUrl(settings.operaBaseUrl()).requestFactory(HttpClients.factory(settings)).build();
  }

  /**
   * {@code postBillingPayment}: the payment goes to the folio window as a {@code Billing} payment, with the hotel's payment method code, the
   * bridge's cashier, our idempotency key as the posting reference (how {@link #find} recognises it) and the processor's reference as the
   * remark. No business date is sent: OPERA posts on its own.
   */
  @Override
  public String postPayment(OperaPayment p) {
    ObjectNode criteria = JSON.createObjectNode();
    criteria.put("hotelId", p.hotelId());
    criteria.putObject("reservationId").put("id", p.reservationId()).put("type", "Reservation");
    criteria.put("folioWindowNo", p.folioWindow());
    criteria.put("cashierId", p.cashierId());
    criteria.putObject("paymentMethod").put("paymentMethod", p.paymentMethod());
    criteria.putObject("postingAmount").putRawValue("amount", new RawValue(p.amount().toDecimalString())).put("currencyCode", p.amount().currency());
    criteria.put("postingReference", p.externalRef());
    criteria.put("postingRemark", "Processor reference " + p.pspRef());
    criteria.put("action", "Billing");
    ObjectNode body = JSON.createObjectNode();
    body.set("criteria", criteria);
    JsonNode reply = call(p.hotelId(), c -> c.post().uri("/csh/v1/hotels/{h}/reservations/{r}/payments", p.hotelId(), p.reservationId())
        .contentType(MediaType.APPLICATION_JSON).body(body.toString()));
    for (JsonNode posting : reply.path("postings")) {
      String transactionNo = posting.path("transactionNo").asText();
      if (PAYMENT.equals(posting.path("transactionType").asText()) && !transactionNo.isBlank()) {
        return transactionNo;
      }
    }
    // It may have posted: "unavailable" makes the next attempt look for it before posting again, instead of recording a blank reference.
    throw new OperaUnavailableException("OPERA answered without a transaction number");
  }

  /**
   * {@code getFolio} with its postings: our payment is the {@code Payment} posting whose reference is our key, in any window. A window longer
   * than one page says {@code hasMore}, and its next page is asked for by window number and offset (Oracle's paging) until the last: the look
   * comes before every post, the first one too, so a long folio must not stop a guest being paid. Anything that cannot prove the payment
   * absent is "unavailable", never "not found", because "not found" means post again and OPERA does not deduplicate (see searchWindow).
   */
  @Override
  public Optional<Posted> find(String hotelId, String reservationId, String externalRef) {
    JsonNode reply = call(hotelId, c -> c.get().uri(FOLIO + "?fetchInstructions={f}", hotelId, reservationId, POSTINGS));
    for (JsonNode window : reply.path("reservationFolioInformation").path("folioWindows")) {
      Optional<Posted> ours = searchWindow(hotelId, reservationId, externalRef, window);
      if (ours.isPresent()) {
        return ours;
      }
    }
    return Optional.empty();
  }

  /**
   * One window, page by page; the next page starts after the postings already read (the offset counts postings). A window proves our
   * payment absent only by a last page that keeps every promise: a page with nothing on it after a promise of more, a window with no number
   * to ask for, a total larger than what was read, or more than {@value #MAX_FOLIO_PAGES} pages are "unavailable". Those are also what a
   * misreading of Oracle's paging would look like (an offset counted in pages lands past the end and returns nothing, or skips pages).
   */
  private Optional<Posted> searchWindow(String hotelId, String reservationId, String externalRef, JsonNode firstPage) {
    JsonNode page = firstPage;
    int read = 0;
    for (int pages = 1; ; pages++) {
      int count = 0;
      for (JsonNode folio : page.path("folios")) {
        for (JsonNode posting : folio.path("postings")) {
          count++;
          if (PAYMENT.equals(posting.path("transactionType").asText()) && externalRef.equals(posting.path("reference").asText())) {
            return Optional.of(found(posting, reservationId));
          }
        }
      }
      read += count;
      boolean hasMore = page.path("hasMore").asBoolean(false);
      if (count == 0 && (hasMore || pages > 1)) {
        throw partialFolio();
      }
      if (!hasMore) {
        if (pages > 1 && page.path("totalResults").isIntegralNumber() && page.path("totalResults").asInt() != read) {
          throw partialFolio();
        }
        return Optional.empty();
      }
      if (pages == MAX_FOLIO_PAGES || !page.path("folioWindowNo").isIntegralNumber()) {
        throw partialFolio();
      }
      page = nextPage(hotelId, reservationId, page.path("folioWindowNo").asInt(), read);
    }
  }

  private JsonNode nextPage(String hotelId, String reservationId, int windowNo, int offset) {
    JsonNode reply = call(hotelId,
        c -> c.get().uri(FOLIO + "?fetchInstructions={f}&folioWindowNo={w}&offset={o}", hotelId, reservationId, POSTINGS, windowNo, offset));
    for (JsonNode window : reply.path("reservationFolioInformation").path("folioWindows")) {
      if (window.path("folioWindowNo").asInt(-1) == windowNo) {
        return window;
      }
    }
    throw partialFolio();
  }

  private static OperaUnavailableException partialFolio() {
    return new OperaUnavailableException("OPERA returned only part of the folio: cannot tell whether our payment is on it");
  }

  /**
   * Our reference and OPERA's transaction number identify the payment; its amount and date are read if they can be and are empty if not, so
   * a field the bridge does not need never turns "found" into "unavailable" (Oracle makes the currency optional, for one).
   */
  private static Posted found(JsonNode posting, String reservationId) {
    String transactionNo = posting.path("transactionNo").asText();
    if (transactionNo.isBlank()) {
      // Our payment is on the folio but cannot be traced: never post it again, and never record it as POSTED with a blank reference.
      throw new OperaUnavailableException("OPERA listed our payment without a transaction number");
    }
    return new Posted(transactionNo, reservationId, amount(posting.path("postedAmount")), date(posting));
  }

  private static Optional<Money> amount(JsonNode amount) {
    String currency = amount.path("currencyCode").asText();
    try {
      return Optional.of(new Money(toMinor(amount.path("amount").asText(), currency), currency));
    } catch (RuntimeException unreadable) {
      return Optional.empty(); // no or an unknown currency, more decimals than it has, not a number
    }
  }

  private static Optional<LocalDate> date(JsonNode posting) {
    try {
      return Optional.of(LocalDate.parse(posting.path("transactionDate").asText(posting.path("postingDate").asText())));
    } catch (DateTimeParseException unreadable) {
      return Optional.empty();
    }
  }

  /** {@code getBusinessDate} answers for a list of hotels: ours is the one with our hotel id. */
  @Override
  public LocalDate businessDate(String hotelId) {
    JsonNode reply = call(hotelId, c -> c.get().uri("/bof/v1/hotels/{h}/businessDate", hotelId));
    return readable("business date", () -> {
      for (JsonNode hotel : reply.path("hotels")) {
        if (hotelId.equals(hotel.path("hotelId").asText())) {
          return LocalDate.parse(hotel.path("businessDate").asText());
        }
      }
      throw new IllegalStateException("no business date for the hotel");
    });
  }

  /** A reply we cannot read is treated like no reply: the caller retries, looks first, and dead-letters as replayable, never crashes. */
  private static <T> T readable(String what, Supplier<T> parse) {
    try {
      return parse.get();
    } catch (RuntimeException unreadable) {
      throw new OperaUnavailableException("OPERA sent a " + what + " we could not read");
    }
  }

  private JsonNode call(String hotelId, Function<RestClient, RestClient.RequestHeadersSpec<?>> request) {
    try {
      return send(hotelId, request, null);
    } catch (TokenExpired expired) {
      return send(hotelId, request, expired.token);
    }
  }

  /** {@code rejectedToken} is null on the first try, or the token OPERA just refused (401), which is then replaced once. */
  private JsonNode send(String hotelId, Function<RestClient, RestClient.RequestHeadersSpec<?>> request, String rejectedToken) {
    boolean freshToken = rejectedToken != null;
    String bearer = token(rejectedToken);
    String body;
    try {
      body = request.apply(http).header("Authorization", "Bearer " + bearer).header("x-hotelid", hotelId).header("x-app-key", settings.operaAppKey())
          .headers(h -> {
            String requestId = MDC.get("requestId");
            if (requestId != null) {
              h.add("x-request-id", requestId); // lets Oracle support find our call in their logs
            }
          })
          .retrieve().body(String.class);
    } catch (HttpClientErrorException e) {
      throw map(e.getStatusCode(), e.getResponseBodyAsString(), freshToken, bearer);
    } catch (HttpServerErrorException e) {
      throw new OperaUnavailableException("OPERA answered " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw new OperaUnavailableException("OPERA did not answer in time"); // connection refused or read timeout: it may or may not have arrived
    }
    if (body == null || body.isBlank()) {
      return MissingNode.getInstance(); // 204: for a folio, no postings yet
    }
    return readable("reply", () -> {
      try {
        return JSON.readTree(body);
      } catch (Exception e) {
        throw new IllegalStateException("not JSON", e);
      }
    });
  }

  /**
   * A 4xx is a rejection of the posting (dead-letter, no replay) only when it is about the posting. Our credentials being refused (401
   * after a fresh token, 403: a rotated secret, an application key or hotel not granted) says nothing about the folio: it is "unavailable",
   * so the posting can be replayed once the configuration is fixed, instead of waiting for a person to repair a folio that is fine (D-34).
   */
  private RuntimeException map(HttpStatusCode status, String body, boolean alreadyRefreshed, String bearer) {
    int code = status.value();
    if (code == 401 && !alreadyRefreshed) {
      return new TokenExpired(bearer);
    }
    if (code == 401 || code == 403) {
      return new OperaUnavailableException("OPERA refused our credentials (" + code + "): check the client id, secret, application key and hotel access");
    }
    if (code == 408 || code == 409 || code == 429) {
      return new OperaUnavailableException("OPERA asked us to wait (" + code + ")");
    }
    String reason = ErrorBodies.code(body);
    if (code == 404 && ErrorBodies.NO_REASON.equals(reason)) {
      // Not OPERA's own answer about the reservation (that carries an error code) but a wrong address or a proxy: ours to fix, replayable.
      return new OperaUnavailableException("OPERA could not be reached at the configured address (404)");
    }
    return new OperaRejectedException("OPERA refused the request (" + code + "): " + reason);
  }

  /**
   * The current token, fetched when there is none or it is about to expire. {@code rejected} is a token OPERA refused: it is replaced only
   * if it is still the current one, so twenty requests that all got a 401 cause one fetch, not twenty. OHIP's client credentials flow: the
   * client id and secret as HTTP Basic, the application key and the enterprise id as headers, the scope in the form.
   */
  private synchronized String token(String rejected) {
    boolean stillValid = accessToken != null && clock.instant().isBefore(tokenExpiresAt.minusSeconds(EXPIRY_MARGIN_SECONDS));
    if (stillValid && !accessToken.equals(rejected)) {
      return accessToken;
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("scope", settings.operaScope());
    try {
      JsonNode reply = http.post().uri("/oauth/v1/tokens").headers(h -> h.setBasicAuth(settings.operaClientId(), settings.operaClientSecret()))
          .header("x-app-key", settings.operaAppKey()).header("enterpriseId", settings.operaEnterpriseId())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
      String token = reply == null ? "" : reply.path("access_token").asText();
      if (token.isBlank()) {
        throw new OperaUnavailableException("OPERA token service answered without a token");
      }
      accessToken = token;
      tokenExpiresAt = clock.instant().plusSeconds(reply.path("expires_in").asLong(60));
      log.info("Fetched a new OPERA access token, valid for {} s", reply.path("expires_in").asLong(60));
      return accessToken;
    } catch (HttpClientErrorException e) {
      // Our credentials, not the posting: replayable once they are fixed, never a rejection of a guest's folio.
      log.error("OPERA refused our credentials ({}): check the client id, secret, application key, enterprise id and scope", e.getStatusCode().value());
      throw new OperaUnavailableException("OPERA refused our credentials (" + e.getStatusCode().value() + "): check the client id, secret and application key");
    } catch (RestClientException e) {
      throw new OperaUnavailableException("OPERA token service did not answer");
    }
  }

  /** A posted amount as minor units. A payment may be listed as a credit (negative); its direction is already known, so the sign is dropped. */
  private static long toMinor(String decimal, String currency) {
    int digits = Money.fractionDigits(currency);
    String[] parts = (decimal.startsWith("-") ? decimal.substring(1) : decimal).split("\\.");
    String fraction = parts.length > 1 ? parts[1] : "";
    if (fraction.length() > digits && !fraction.substring(digits).chars().allMatch(c -> c == '0')) {
      throw new IllegalStateException("more decimals than the currency has");
    }
    return Long.parseLong(parts[0] + (fraction + "0".repeat(digits)).substring(0, digits));
  }

  private static final class TokenExpired extends RuntimeException {
    private final transient String token;

    TokenExpired(String token) {
      super("token expired", null, false, false);
      this.token = token;
    }
  }
}
