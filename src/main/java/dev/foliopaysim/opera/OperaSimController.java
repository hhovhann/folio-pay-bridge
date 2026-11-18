package dev.foliopaysim.opera;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The OHIP operations a payment bridge uses, on the paths, headers and shapes of Oracle's published specification (Swagger files in
 * oracle/hospitality-api-docs; {@code OhipContractTest} checks every exchange against them): {@code getToken}, {@code getBusinessDate},
 * {@code postBillingPayment} and {@code getFolio}. Errors are Oracle's problem details, with the application code in {@code o:errorCode}.
 * Under {@code /sim} is the front desk: reservations, folios, check-in and check-out, night audit, and the switches that make it fail.
 */
@RestController
@Profile("opera")
class OperaSimController {
  private static final long TIMEOUT_AFTER_POST_MILLIS = 4_000;
  private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
  private final OperaSimulator pms;
  private final String bridgeUrl;
  private final Clock clock;
  private final RestClient bridge = RestClient.create();

  OperaSimController(OperaSimulator pms, Clock clock, @Value("${sim.opera.bridge-url:}") String bridgeUrl) {
    this.pms = pms;
    this.clock = clock;
    this.bridgeUrl = bridgeUrl;
  }

  // ---- OHIP: authentication (getToken)
  /** Client credentials: the client id and secret as HTTP Basic, the application key and enterprise id as headers, a scope in the form. */
  @PostMapping(value = "/oauth/v1/tokens", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  ResponseEntity<Object> token(@RequestHeader(value = "x-app-key", required = false) String appKey, @RequestHeader(value = "enterpriseId", required = false) String enterpriseId,
                               @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                               @RequestParam(value = "grant_type", required = false) String grantType, @RequestParam(value = "scope", required = false) String scope) {
    if (pms.fault() == OperaSimulator.Fault.DOWN) {
      return problem(HttpStatus.SERVICE_UNAVAILABLE, "PMS_UNAVAILABLE", "OPERA is down");
    }
    if (!"client_credentials".equals(grantType) || scope == null || scope.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid_request", "error_description", "grant_type client_credentials and a scope are required"));
    }
    String[] basic = basic(authorization);
    return pms.issueToken(appKey, enterpriseId, basic[0], basic[1])
        .<ResponseEntity<Object>>map(t -> ResponseEntity.ok(Map.of("access_token", t.value(), "token_type", "Bearer", "expires_in", pms.tokenTtlSeconds(),
            "oracle_tk_context", "user_assertion")))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_client", "error_description", "Bad application key, enterprise or client credentials")));
  }

  private static String[] basic(String authorization) {
    try {
      String decoded = new String(Base64.getDecoder().decode(authorization.substring("Basic ".length())), StandardCharsets.UTF_8);
      int colon = decoded.indexOf(':');
      return new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)};
    } catch (RuntimeException missingOrMalformed) {
      return new String[] {"", ""};
    }
  }

  // ---- OHIP: back office (getBusinessDate)
  @GetMapping("/bof/v1/hotels/{hotelId}/businessDate")
  ResponseEntity<Object> businessDate(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestHeader(value = "x-hotelid", required = false) String hotelHeader,
                                      @RequestHeader(value = "x-app-key", required = false) String appKeyHeader, @PathVariable String hotelId) {
    ResponseEntity<Object> refused = guard(authorization, hotelHeader, appKeyHeader, hotelId);
    if (refused != null) {
      return refused;
    }
    ObjectNode body = JSON.createObjectNode();
    body.putArray("hotels").addObject().put("hotelId", hotelId).put("businessDate", pms.businessDate().toString());
    return ResponseEntity.ok(body);
  }

  // ---- OHIP: cashiering (postBillingPayment, getFolio)
  @PostMapping("/csh/v1/hotels/{hotelId}/reservations/{reservationId}/payments")
  ResponseEntity<Object> postPayment(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestHeader(value = "x-hotelid", required = false) String hotelHeader,
                                     @RequestHeader(value = "x-app-key", required = false) String appKeyHeader, @PathVariable String hotelId, @PathVariable String reservationId,
                                     @RequestBody String body) throws InterruptedException {
    ResponseEntity<Object> refused = guard(authorization, hotelHeader, appKeyHeader, hotelId);
    if (refused != null) {
      return refused;
    }
    if (pms.consume(OperaSimulator.Fault.THROTTLE_NEXT)) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "1").body(Map.of("title", "Too Many Requests", "status", 429));
    }
    if (pms.consume(OperaSimulator.Fault.LOCK_CONFLICT_NEXT)) {
      return problem(HttpStatus.CONFLICT, "RESERVATION_LOCKED", "Another workstation is editing this reservation");
    }
    JsonNode criteria = read(body).path("criteria");
    if (!criteria.isObject()) {
      return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "criteria is required");
    }
    Optional<OperaSimulator.Reservation> reservation = pms.reservation(reservationId);
    if (reservation.isEmpty()) {
      return problem(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "No such reservation");
    }
    String currency = criteria.path("postingAmount").path("currencyCode").asText();
    Optional<Long> amount = OperaSimulator.minor(criteria.path("postingAmount").path("amount").asText(), currency);
    String method = criteria.path("paymentMethod").path("paymentMethod").asText();
    long cashier = criteria.path("cashierId").asLong(-1);
    int window = criteria.path("folioWindowNo").asInt(1);
    String invalid = !reservationId.equals(criteria.path("reservationId").path("id").asText()) ? "criteria.reservationId does not match the path"
        : amount.isEmpty() ? "postingAmount needs a positive amount in a currency's own decimals"
        : !OperaSimulator.PAYMENT_METHODS.contains(method) ? "Unknown payment method " + method
        : !OperaSimulator.CASHIERS.containsKey(cashier) ? "Unknown cashier " + cashier
        : window < 1 || window > 8 ? "Folio window must be 1 to 8"
        : !criteria.path("action").asText("Billing").equals("Billing") ? "This simulator posts Billing payments only"
        : null;
    if (invalid != null) {
      return problem(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT", invalid);
    }
    if (reservation.get().status() == OperaSimulator.Status.CHECKED_OUT) {
      return problem(HttpStatus.BAD_REQUEST, "FOLIO_CLOSED", "The reservation is checked out and its folio is closed");
    }
    // A real PMS does not deduplicate: posting the same reference twice posts twice. The bridge must look first.
    OperaSimulator.Posting posting = pms.postPayment(hotelId, reservationId, window, method, cashier, amount.get(), currency,
        criteria.path("postingReference").asText(""), criteria.path("postingRemark").asText(""));
    if (pms.consume(OperaSimulator.Fault.TIMEOUT_AFTER_POST)) {
      Thread.sleep(TIMEOUT_AFTER_POST_MILLIS); // posted, but the reply arrives too late
    }
    ObjectNode reply = JSON.createObjectNode();
    postingJson(reply.putArray("postings"), posting);
    return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.LOCATION, "/csh/v1/hotels/" + hotelId + "/reservations/" + reservationId + "/folios").body(reply);
  }

  /** The reservation's folio windows with their postings and balances; the bridge reads it to find a payment it may already have posted. */
  @GetMapping("/csh/v1/hotels/{hotelId}/reservations/{reservationId}/folios")
  ResponseEntity<Object> folio(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestHeader(value = "x-hotelid", required = false) String hotelHeader,
                               @RequestHeader(value = "x-app-key", required = false) String appKeyHeader, @PathVariable String hotelId, @PathVariable String reservationId) {
    ResponseEntity<Object> refused = guard(authorization, hotelHeader, appKeyHeader, hotelId);
    if (refused != null) {
      return refused;
    }
    Optional<OperaSimulator.Reservation> reservation = pms.reservation(reservationId);
    if (reservation.isEmpty()) {
      return problem(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "No such reservation");
    }
    ObjectNode body = JSON.createObjectNode();
    ObjectNode info = body.putObject("reservationFolioInformation");
    info.putObject("reservationInfo").put("hotelId", hotelId).putArray("reservationIdList").addObject().put("id", reservationId).put("type", "Reservation");
    ArrayNode windows = info.putArray("folioWindows");
    List<OperaSimulator.Posting> postings = pms.folio(reservationId);
    String currency = reservation.get().currency();
    postings.stream().map(OperaSimulator.Posting::folioWindowNo).collect(Collectors.toCollection(TreeSet::new)).forEach(w -> {
      ObjectNode window = windows.addObject();
      window.put("folioWindowNo", w);
      window.putObject("balance").putRawValue("amount", new RawValue(OperaSimulator.decimal(pms.balanceMinor(reservationId, w), currency))).put("currencyCode", currency);
      window.put("emptyWindow", false);
      window.put("hasMore", false);
      ArrayNode lines = window.putArray("folios").addObject().put("folioStatus", "Ok").putArray("postings");
      postings.stream().filter(p -> p.folioWindowNo() == w).forEach(p -> postingJson(lines, p));
    });
    return ResponseEntity.ok(body);
  }

  private static void postingJson(ArrayNode into, OperaSimulator.Posting p) {
    ObjectNode line = into.addObject();
    line.put("transactionNo", p.transactionNo());
    line.put("transactionType", p.type());
    line.put("transactionCode", p.code());
    line.put("transactionDescription", p.description());
    line.putObject("postedAmount").putRawValue("amount", new RawValue(p.amount())).put("currencyCode", p.currency());
    line.putObject(p.type().equals("Payment") ? "creditAmount" : "debitAmount").putRawValue("amount", new RawValue(p.amount())).put("currencyCode", p.currency());
    line.put("reference", p.reference());
    line.put("remark", p.remark());
    line.put("folioWindowNo", p.folioWindowNo());
    line.put("transactionDate", p.businessDate().toString());
    line.put("postingDate", p.businessDate().toString());
    line.putObject("cashierInfo").put("cashierId", p.cashierId()).put("cashierName", p.cashierName());
  }

  private static JsonNode read(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception notJson) {
      return JSON.createObjectNode();
    }
  }

  // ---- the front desk and the simulator's switches (not part of OHIP)
  record BookingRequest(String bookingRef, String ratePlan, int arrivalInDays, long totalMinor) {}

  /** The PMS takes a new reservation and announces it to the bridge, the way a real PMS interface would. */
  @PostMapping("/sim/booking-events")
  Map<String, Object> bookingEvent(@RequestBody BookingRequest request) {
    if (bridgeUrl.isBlank()) {
      return Map.of("error", "No bridge configured: set sim.opera.bridge-url");
    }
    LocalDate arrival = LocalDate.now(clock).plusDays(request.arrivalInDays());
    pms.book(request.bookingRef(), "Synthetic guest " + request.bookingRef(), arrival, 2, request.totalMinor(), "USD");
    Map<String, Object> event = Map.of("type", "NEW", "bookingRef", request.bookingRef(), "folioId", request.bookingRef(), "ratePlan", request.ratePlan(),
        "arrival", arrival.toString(), "totalMinor", request.totalMinor(), "currency", "USD");
    try {
      Map<?, ?> reply = bridge.post().uri(bridgeUrl + "/api/v1/booking-events").contentType(MediaType.APPLICATION_JSON).body(event).retrieve().body(Map.class);
      return Map.of("note", "Bridge answered: " + reply.get("note"));
    } catch (RestClientException e) {
      return Map.of("error", "The bridge did not accept the event");
    }
  }

  @GetMapping("/sim/state")
  Map<String, Object> state() { return pms.state(); }

  @GetMapping("/sim/reservations/{id}/folio")
  ResponseEntity<Object> frontDeskFolio(@PathVariable String id) {
    return pms.reservation(id).<ResponseEntity<Object>>map(r -> ResponseEntity.ok(Map.of("reservation", r, "balance", OperaSimulator.decimal(pms.balanceMinor(id, 1), r.currency()),
        "postings", pms.folio(id).stream().map(OperaSimulator::row).toList()))).orElseGet(() -> problem(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "No such reservation"));
  }

  @PostMapping("/sim/reservations/{id}/check-in")
  ResponseEntity<Object> checkIn(@PathVariable String id) { return frontDesk(pms.checkIn(id)); }

  @PostMapping("/sim/reservations/{id}/check-out")
  ResponseEntity<Object> checkOut(@PathVariable String id) { return frontDesk(pms.checkOut(id)); }

  @PostMapping("/sim/reservations/{id}/room-charge")
  ResponseEntity<Object> roomCharge(@PathVariable String id) {
    return frontDesk(pms.postRoomCharge(id, 100L).isPresent() ? Optional.empty() : Optional.of("Room charges post only to an in-house guest with a rate"));
  }

  private ResponseEntity<Object> frontDesk(Optional<String> refusal) {
    return refusal.<ResponseEntity<Object>>map(reason -> problem(HttpStatus.BAD_REQUEST, "FRONT_DESK_REFUSED", reason)).orElseGet(() -> ResponseEntity.ok(pms.state()));
  }

  @PostMapping("/sim/faults")
  ResponseEntity<Object> fault(@RequestBody Map<String, String> body) {
    try {
      pms.arm(OperaSimulator.Fault.valueOf(body.getOrDefault("fault", "NONE")));
    } catch (IllegalArgumentException unknown) {
      return problem(HttpStatus.BAD_REQUEST, "UNKNOWN_FAULT", "Unknown fault");
    }
    return ResponseEntity.ok(pms.state());
  }

  @PostMapping("/sim/night-audit")
  Map<String, Object> nightAudit() {
    pms.nightAudit();
    return pms.state();
  }

  @PostMapping("/sim/expire-tokens")
  Map<String, Object> expireTokens() {
    pms.expireTokens();
    return pms.state();
  }

  @PostMapping("/sim/reset")
  Map<String, Object> reset() {
    pms.reset();
    return pms.state();
  }

  @GetMapping("/")
  ResponseEntity<Void> home() { return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/sim/opera.html").build(); }

  /** Down means down for every call; a bad or expired token is 401 so the client must fetch a new one. */
  private ResponseEntity<Object> guard(String authorization, String hotelHeader, String appKeyHeader, String hotelId) {
    if (!pms.appKeyMatches(appKeyHeader)) {
      return problem(HttpStatus.UNAUTHORIZED, "INVALID_APP_KEY", "The x-app-key header is missing or wrong");
    }
    if (!hotelId.equals(hotelHeader)) {
      return problem(HttpStatus.BAD_REQUEST, "HOTEL_HEADER_MISMATCH", "The x-hotelid header must name the hotel in the path");
    }
    if (pms.fault() == OperaSimulator.Fault.DOWN) {
      return problem(HttpStatus.SERVICE_UNAVAILABLE, "PMS_UNAVAILABLE", "OPERA is down");
    }
    String bearer = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
    return pms.tokenValid(bearer) ? null : problem(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Access token missing, bad or expired");
  }

  /** Oracle's {@code exceptionDetailType}: an RFC 7807 problem detail, with OPERA's own code in {@code o:errorCode}. */
  private static ResponseEntity<Object> problem(HttpStatus status, String code, String detail) {
    ObjectNode body = JSON.createObjectNode();
    body.put("type", "https://www.rfc-editor.org/rfc/rfc9110.html#name-" + status.value());
    body.put("title", status.getReasonPhrase());
    body.put("status", status.value());
    body.put("detail", detail);
    body.put("o:errorCode", code);
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
  }
}
