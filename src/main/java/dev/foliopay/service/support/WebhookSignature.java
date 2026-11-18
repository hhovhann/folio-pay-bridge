package dev.foliopay.service.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs a webhook so the receiver can check it came from us and was not changed or replayed: {@code v1=} HMAC-SHA256 of
 * {@code "<timestamp>.<body>"} with the shared secret. The receiver recomputes it, compares in constant time, and refuses a timestamp
 * older than a few minutes. The same scheme as the large payment providers, so receivers know how to verify it.
 */
public final class WebhookSignature {
  private WebhookSignature() {}

  public static String sign(String secret, long timestampSeconds, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
      return "v1=" + HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is always available", e);
    }
  }
}
