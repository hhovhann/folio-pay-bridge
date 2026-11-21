package dev.foliopay.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How long a card number is kept, and how the purge runs (PCI DSS v4.0.1 3.2.1, D-44). {@code retention} counts from the card's last use:
 * storing it, or opening it for an authorisation. Set it longer than the hotel's longest time from booking to check-out plus its window
 * for late charges, or a card stored for a stay far ahead is purged before the stay. The values live in {@code application.properties},
 * where an operator sees them; none has a default here.
 */
@Validated
@ConfigurationProperties("foliopay.vault")
public record VaultProperties(@NotNull Duration retention, @NotNull Duration purgeInterval, @Min(1) @Max(1_000) int purgeBatch) {
  static final Duration MIN_RETENTION = Duration.ofDays(1);

  public VaultProperties {
    if (retention != null && retention.compareTo(MIN_RETENTION) < 0) {
      throw new IllegalStateException("foliopay.vault.retention must be at least one day: a shorter one would purge cards still in use");
    }
  }
}
