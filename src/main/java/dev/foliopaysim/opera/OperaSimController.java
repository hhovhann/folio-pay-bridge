package dev.foliopaysim.opera;

import java.time.LocalDate;
import java.util.Map;
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

/** An OHIP-shaped REST surface (OAuth client credentials, x-app-key and x-hotelid headers, bearer tokens; the resource paths are simplified, not Oracle's): OAuth token, business date, post a payment to a reservation's folio window, find by reference. */
@RestController
@Profile("opera")
class OperaSimController {
  private static final long TIMEOUT_AFTER_POST_MILLIS = 4_000;
  private final OperaSimulator pms;
  private final String bridgeUrl;
  private final java.time.Clock clock;
  private final org.springframework.web.client.RestClient bridge = org.springframework.web.client.RestClient.create();

  OperaSimController(OperaSimulator pms, java.time.Clock clock, @org.springframework.beans.factory.annotation.Value("${sim.opera.bridge-url:}") String bridgeUrl) {
    this.pms = pms;
    this.clock = clock;
    this.bridgeUrl = bridgeUrl;
  }

  record BookingRequest(String bookingRef, String ratePlan, int arrivalInDays, long totalMinor) {}

  /** The PMS announces a new reservation to the bridge, the way a real PMS interface would. */
  @PostMapping("/sim/booking-events")
  Map<String, Object> bookingEvent(@RequestBody BookingRequest request) {
    if (bridgeUrl.isBlank()) return Map.of("error", "No bridge configured: set sim.opera.bridge-url");
    Map<String, Object> event = Map.of("type", "NEW", "bookingRef", request.bookingRef(), "folioId", request.bookingRef(), "ratePlan", request.ratePlan(),
        "arrival", LocalDate.now(clock).plusDays(request.arrivalInDays()).toString(), "totalMinor", request.totalMinor(), "currency", "USD");
    try {
      Map<?, ?> reply = bridge.post().uri(bridgeUrl + "/api/booking-events").contentType(MediaType.APPLICATION_JSON).body(event).retrieve().body(Map.class);
      return Map.of("note", "Bridge answered: " + reply.get("note"));
    } catch (org.springframework.web.client.RestClientException e) {
      return Map.of("error", "The bridge did not accept the event");
    }
  }

  record PaymentRequest(int folioWindow, String paymentCode, String cashierId, String amount, String currency, LocalDate businessDate, String reference, String pspRef) {}

  @PostMapping(value = "/oauth/v1/tokens", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  ResponseEntity<Object> token(@RequestHeader(value = "x-app-key", required = false) String appKey, @RequestParam("client_id") String clientId,
                               @RequestParam("client_secret") String clientSecret, @RequestParam("grant_type") String grantType) {
    if (pms.fault() == OperaSimulator.Fault.DOWN) return error(HttpStatus.SERVICE_UNAVAILABLE, "PMS_UNAVAILABLE", "OPERA is down");
    return pms.issueToken(appKey, clientId, clientSecret)
        .<ResponseEntity<Object>>map(t -> ResponseEntity.ok(Map.of("access_token", t.value(), "token_type", "bearer", "expires_in", pms.tokenTtlSeconds())))
        .orElseGet(() -> error(HttpStatus.UNAUTHORIZED, "INVALID_CLIENT", "Bad application key or client credentials"));
  }

  @GetMapping("/v1/hotels/{hotelId}/business-date")
  ResponseEntity<Object> businessDate(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestHeader(value = "x-hotelid", required = false) String hotelHeader, @RequestHeader(value = "x-app-key", required = false) String appKeyHeader, @PathVariable String hotelId) {
    ResponseEntity<Object> refused = guard(authorization, hotelHeader, appKeyHeader, hotelId);
    return refused != null ? refused : ResponseEntity.ok(Map.of("hotelId", hotelId, "businessDate", pms.businessDate().toString()));
  }

  @PostMapping("/v1/hotels/{hotelId}/reservations/{reservationId}/payments")
  ResponseEntity<Object> post(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestHeader(value = "x-hotelid", required = false) String hotelHeader, @RequestHeader(value = "x-app-key", required = false) String appKeyHeader,
                              @PathVariable String hotelId, @PathVariable String reservationId, @RequestBody PaymentRequest request) throws InterruptedException {
    ResponseEntity<Object> refused = guard(authorization, hotelHeader, appKeyHeader, hotelId);
    if (refused != null) return refused;
    if (pms.consume(OperaSimulator.Fault.THROTTLE_NEXT)) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "1").body(Map.of("code", "RATE_LIMITED", "message", "Slow down"));
    if (pms.consume(OperaSimulator.Fault.LOCK_CONFLICT_NEXT)) return error(HttpStatus.CONFLICT, "RESERVATION_LOCKED", "Another workstation is editing this reservation");
    if (!pms.reservationExists(reservationId)) return error(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "No such reservation");
    if (pms.reservationClosed(reservationId)) return error(HttpStatus.BAD_REQUEST, "FOLIO_CLOSED", "The folio is closed");
    if (request.paymentCode() == null || request.paymentCode().isBlank() || request.cashierId() == null || request.cashierId().isBlank()) return error(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT", "Payment code and cashier are required");
    if (!pms.businessDate().equals(request.businessDate())) return error(HttpStatus.BAD_REQUEST, "BUSINESS_DATE_MISMATCH", "The hotel business date is " + pms.businessDate());
    // A real PMS does not deduplicate: posting the same reference twice posts twice. The bridge must look first.
    OperaSimulator.Payment payment = pms.post(hotelId, reservationId, request.folioWindow(), request.paymentCode(), request.cashierId(), request.amount(), request.currency(), request.businessDate(), request.reference(), request.pspRef());
    if (pms.consume(OperaSimulator.Fault.TIMEOUT_AFTER_POST)) Thread.sleep(TIMEOUT_AFTER_POST_MILLIS); // posted, but the reply arrives too late
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("transactionNo", payment.transactionNo()));
  }

  @GetMapping("/v1/hotels/{hotelId}/payments")
  ResponseEntity<Object> find(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestHeader(value = "x-hotelid", required = false) String hotelHeader, @RequestHeader(value = "x-app-key", required = false) String appKeyHeader,
                              @PathVariable String hotelId, @RequestParam("reference") String reference) {
    ResponseEntity<Object> refused = guard(authorization, hotelHeader, appKeyHeader, hotelId);
    return refused != null ? refused : ResponseEntity.ok(Map.of("payments", pms.find(hotelId, reference)));
  }

  // ---- simulator controls and the front-desk view (not part of the OPERA surface)
  @GetMapping("/sim/state")
  Map<String, Object> state() { return pms.state(); }

  @PostMapping("/sim/faults")
  ResponseEntity<Object> fault(@RequestBody Map<String, String> body) {
    try {
      pms.arm(OperaSimulator.Fault.valueOf(body.getOrDefault("fault", "NONE")));
    } catch (IllegalArgumentException unknown) {
      return error(HttpStatus.BAD_REQUEST, "UNKNOWN_FAULT", "Unknown fault");
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
    if (!pms.appKeyMatches(appKeyHeader)) return error(HttpStatus.UNAUTHORIZED, "INVALID_APP_KEY", "The x-app-key header is missing or wrong");
    if (!hotelId.equals(hotelHeader)) return error(HttpStatus.BAD_REQUEST, "HOTEL_HEADER_MISMATCH", "The x-hotelid header must name the hotel in the path");
    if (pms.fault() == OperaSimulator.Fault.DOWN) return error(HttpStatus.SERVICE_UNAVAILABLE, "PMS_UNAVAILABLE", "OPERA is down");
    String bearer = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
    return pms.tokenValid(bearer) ? null : error(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Access token missing, bad or expired");
  }

  private static ResponseEntity<Object> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
  }
}
