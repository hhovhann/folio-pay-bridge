package dev.foliopaysim.opera;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hotel's PMS middleware receiving the bridge's webhooks, the way an integrator should: it recomputes the HMAC over timestamp and
 * body, compares in constant time, refuses a stale timestamp, and ignores an event id it has already seen. Keeps the last 50 for the page.
 */
@RestController
@Profile("opera")
class WebhookReceiverController {
  private static final long MAX_AGE_SECONDS = 300;
  private final String secret;
  private final Deque<Map<String, Object>> received = new ArrayDeque<>();

  WebhookReceiverController(@Value("${sim.opera.webhook-secret:}") String secret) {
    this.secret = secret;
  }

  @PostMapping("/sim/webhooks")
  synchronized ResponseEntity<Void> receive(@RequestHeader("Webhook-Id") String id, @RequestHeader("Webhook-Timestamp") long timestamp,
                                            @RequestHeader("Webhook-Signature") String signature, @RequestBody String body) throws Exception {
    boolean fresh = Math.abs(Instant.now().getEpochSecond() - timestamp) <= MAX_AGE_SECONDS;
    boolean verified = !secret.isBlank() && fresh && MessageDigest.isEqual(sign(timestamp, body)
        .getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    if (!verified) {
      return ResponseEntity.status(401).build();
    }
    boolean duplicate = received.stream().anyMatch(e -> id.equals(e.get("id")));
    if (!duplicate) {
      received.addFirst(Map.of("id", id, "receivedAt", Instant.now().toString(), "body", body));
      while (received.size() > 50) {
        received.removeLast();
      }
    }
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/sim/webhooks")
  synchronized List<Map<String, Object>> list() { return List.copyOf(received); }

  private String sign(long timestamp, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "v1=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
  }
}
