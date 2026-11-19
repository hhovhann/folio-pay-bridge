package dev.foliopay.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where events go and how they are signed (D-35). Off when {@code url} is empty. The URL comes from configuration only, never from a
 * request, so it cannot be pointed at an internal address by a caller (SSRF). It must be HTTPS unless plain HTTP is allowed by name (the
 * Compose demo), and the signing secret must be long enough to resist guessing. {@code lookback} (30 days) is how far back the relay looks
 * for payments to announce: if the relay is stopped for longer than that, older events are not sent (alarm on it well before). The
 * timeouts bound one send: a slow receiver delays only its own retry, and the send lease is derived from them.
 */
@ConfigurationProperties("foliopay.webhook")
public record WebhookProperties(
    String url,
    String secret,
    @DefaultValue("12") int maxAttempts,
    @DefaultValue("5s") Duration poll,
    @DefaultValue("false") boolean allowPlainHttp,
    @DefaultValue("30d") Duration lookback,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("5s") Duration readTimeout) {
  static final int MIN_SECRET_LENGTH = 32;
  private static final Duration MIN_SEND_LEASE = Duration.ofMinutes(1);

  /**
   * How long an instance holds an event while it sends it: two whole sends, never under a minute, so a send that is still running is not
   * claimed and sent by another instance as well (agent log 32: leases come from the timeouts, not a fixed number).
   */
  public Duration sendLease() {
    Duration twoSends = connectTimeout.plus(readTimeout).multipliedBy(2);
    return twoSends.compareTo(MIN_SEND_LEASE) > 0 ? twoSends : MIN_SEND_LEASE;
  }


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
