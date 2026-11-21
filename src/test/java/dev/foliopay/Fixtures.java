package dev.foliopay;

import dev.foliopay.config.WebhookProperties;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.service.ChargeService.ChargeCommand;
import java.time.Duration;
import java.time.LocalDate;

/** Small builders so a test says only what it cares about. */
public final class Fixtures {
  public static final LocalDate DAY = LocalDate.of(2026, 11, 17);

  private Fixtures() {}

  public static Charge charge(String key, String bookingRef, String token, Money amount) {
    return new Charge(Charge.BRIDGE_CLIENT, key, bookingRef, "F-1", DAY, token, amount);
  }

  public static ChargeCommand command(String key, String bookingRef, String token, Money amount) {
    return new ChargeCommand(Charge.BRIDGE_CLIENT, key, bookingRef, "F-1", DAY, token, amount);
  }

  /** Webhook settings with the shipped timeouts, back-off and batch: a test names only the address, the secret and the attempts. */
  public static WebhookProperties webhook(String url, String secret, int maxAttempts) {
    return new WebhookProperties(url, secret, maxAttempts, Duration.ofSeconds(5), false, Duration.ofDays(30), Duration.ofSeconds(2),
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofHours(1), 100);
  }

  public static Posting posting(String key, String chargeId, String folioId, Money amount, LocalDate day) {
    return new Posting(key, chargeId, folioId, amount, day, "cap_1");
  }
}
