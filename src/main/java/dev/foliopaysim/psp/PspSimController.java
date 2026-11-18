package dev.foliopaysim.psp;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A processor-shaped REST surface (simplified): authorize, capture, void, settlement. Requires an API key and an Idempotency-Key on every money call. */
@RestController
@Profile("psp")
class PspSimController {
  private static final long TIMEOUT_AFTER_CAPTURE_MILLIS = 4_000;
  private final PspSimulator psp;
  private final String apiKey;

  PspSimController(PspSimulator psp, @Value("${sim.psp.api-key:demo-psp-key}") String apiKey) {
    this.psp = psp;
    this.apiKey = apiKey;
  }

  record AuthRequest(String pan, String expiry, long amountMinor, String currency) {
    @Override public String toString() { return "AuthRequest[redacted]"; } // a card number must never reach a log line
  }

  record CaptureRequest(String authRef, long amountMinor, String currency) {}

  record VoidRequest(String authRef) {}

  @PostMapping("/v1/authorizations")
  ResponseEntity<Object> authorize(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody AuthRequest request) {
    ResponseEntity<Object> refused = guard(authorization, key);
    if (refused != null) {
      return refused;
    }
    var repeat = psp.seen(key);
    if (repeat.isPresent()) {
      return replay(repeat.get(), request.amountMinor(), request.currency());
    }
    if (psp.consume(PspSimulator.Fault.DECLINE_NEXT)) {
      return error(HttpStatus.PAYMENT_REQUIRED, "DO_NOT_HONOR", "Do not honor");
    }
    PspSimulator.Entry auth = psp.once(key, () -> psp.entry("AUTH", last4(request.pan()), request.amountMinor(), request.currency(), "APPROVED", ""));
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ref", auth.ref()));
  }

  @PostMapping("/v1/captures")
  ResponseEntity<Object> capture(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody CaptureRequest request) throws InterruptedException {
    ResponseEntity<Object> refused = guard(authorization, key);
    if (refused != null) {
      return refused;
    }
    var repeat = psp.seen(key);
    if (repeat.isPresent()) {
      return replay(repeat.get(), request.amountMinor(), request.currency());
    }
    var auth = psp.authorisation(request.authRef());
    if (auth.isEmpty()) {
      return error(HttpStatus.NOT_FOUND, "UNKNOWN_AUTHORIZATION", "No such authorisation");
    }
    if (auth.get().amountMinor() != request.amountMinor() || !auth.get().currency().equals(request.currency())) {
      return error(HttpStatus.UNPROCESSABLE_ENTITY, "AMOUNT_MISMATCH", "The capture does not match the authorised amount");
    }
    if (psp.consume(PspSimulator.Fault.DECLINE_CAPTURE_NEXT)) {
      return error(HttpStatus.PAYMENT_REQUIRED, "CAPTURE_DECLINED", "Capture declined");
    }
    PspSimulator.Entry capture = psp.once(key, () -> psp.entry("CAPTURE", "", request.amountMinor(), request.currency(), "CAPTURED", request.authRef()));
    if (psp.consume(PspSimulator.Fault.TIMEOUT_AFTER_CAPTURE)) Thread.sleep(TIMEOUT_AFTER_CAPTURE_MILLIS); // captured, but the reply arrives too late
    return created(capture);
  }

  @PostMapping("/v1/voids")
  ResponseEntity<Object> voidAuthorization(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody VoidRequest request) {
    ResponseEntity<Object> refused = guard(authorization, key);
    if (refused != null) {
      return refused;
    }
    var repeat = psp.seen(key);
    if (repeat.isPresent()) {
      return created(repeat.get());
    }
    if (psp.authorisation(request.authRef()).isEmpty()) {
      return error(HttpStatus.NOT_FOUND, "UNKNOWN_AUTHORIZATION", "No such authorisation");
    }
    PspSimulator.Entry voided = psp.once(key, () -> psp.entry("VOID", "", 0, "", "RELEASED", request.authRef()));
    return created(voided);
  }

  @GetMapping("/v1/settlements")
  ResponseEntity<Object> settlements(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    if (!("Bearer " + apiKey).equals(authorization)) {
      return error(HttpStatus.UNAUTHORIZED, "BAD_API_KEY", "Missing or wrong API key");
    }
    List<Map<String, Object>> lines = psp.settlement().stream().map(e -> Map.<String, Object>of("pspRef", e.ref(), "amountMinor", e.amountMinor(), "currency", e.currency())).toList();
    return ResponseEntity.ok(Map.of("lines", lines));
  }

  @GetMapping("/sim/state")
  Map<String, Object> state() { return psp.state(); }

  @PostMapping("/sim/faults")
  ResponseEntity<Object> fault(@RequestBody Map<String, String> body) {
    try {
      psp.arm(PspSimulator.Fault.valueOf(body.getOrDefault("fault", "NONE")));
    } catch (IllegalArgumentException unknown) {
      return error(HttpStatus.BAD_REQUEST, "UNKNOWN_FAULT", "Unknown fault");
    }
    return ResponseEntity.ok(psp.state());
  }

  @PostMapping("/sim/reset")
  Map<String, Object> reset() {
    psp.reset();
    return psp.state();
  }

  @GetMapping("/")
  ResponseEntity<Void> home() { return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/sim/psp.html").build(); }

  private ResponseEntity<Object> guard(String authorization, String idempotencyKey) {
    if (!("Bearer " + apiKey).equals(authorization)) {
      return error(HttpStatus.UNAUTHORIZED, "BAD_API_KEY", "Missing or wrong API key");
    }
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Every money call needs an Idempotency-Key");
    }
    return null;
  }

  /** Like a real processor: a repeated key returns the first answer, unless the request is not the same one (then 409, final). */
  private static ResponseEntity<Object> replay(PspSimulator.Entry first, long amountMinor, String currency) {
    if (first.amountMinor() != amountMinor || !first.currency().equals(currency)) {
      return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "This idempotency key was used for a different request");
    }
    return created(first);
  }

  private static ResponseEntity<Object> created(PspSimulator.Entry entry) {
    return entry.authRef().isEmpty()
        ? ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ref", entry.ref()))
        : ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ref", entry.ref(), "authRef", entry.authRef()));
  }

  private static String last4(String pan) { return pan == null || pan.length() < 4 ? "" : pan.substring(pan.length() - 4); }

  private static ResponseEntity<Object> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
  }
}
