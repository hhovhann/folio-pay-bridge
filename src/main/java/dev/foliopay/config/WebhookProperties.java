package dev.foliopay.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where events go and how they are signed (D-35). Off when {@code url} is empty. The URL comes from configuration only, never from a
 * request, so it cannot be pointed at an internal address by a caller (SSRF). It must be HTTPS unless plain HTTP is allowed by name (the
 * Compose demo), and the signing secret must be long enough to resist guessing. {@code lookback} (30 days) is how far back the relay looks
 * for payments to announce: if the relay is stopped for longer than that, older events are not sent (alarm on it well before).
 */
@ConfigurationProperties("foliopay.webhook")
public record WebhookProperties(
    String url,
    String secret,
    @DefaultValue("12") int maxAttempts,
    @DefaultValue("5s") Duration poll,
    @DefaultValue("false") boolean allowPlainHttp,
    @DefaultValue("30d") Duration lookback) {
  static final int MIN_SECRET_LENGTH = 32;

  @Override
  public String toString() { return "WebhookProperties[redacted]"; } // holds the signing secret

  /** Called when webhooks are on: refuses to start with a missing or weak secret, or a plain-HTTP address that was not allowed by name. */
  public void requireUsable() {
    if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
      throw new IllegalStateException("foliopay.webhook.secret must be at least " + MIN_SECRET_LENGTH + " characters when foliopay.webhook.url is set");
    }
    String scheme = URI.create(url).getScheme();
    if (!"https".equals(scheme) && !("http".equals(scheme) && allowPlainHttp)) {
      throw new IllegalStateException("foliopay.webhook.url must be https (set foliopay.webhook.allow-plain-http=true for a local demo only)");
    }
  }
}
