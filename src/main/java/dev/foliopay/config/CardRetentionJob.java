package dev.foliopay.config;

import dev.foliopay.logging.Redacted;
import dev.foliopay.service.CardRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the card purge every {@code foliopay.vault.purge-interval} (D-44). A failed run is logged and the next one carries on. */
@Configuration
@EnableScheduling
class CardRetentionJob {
  private static final Logger log = LoggerFactory.getLogger(CardRetentionJob.class);
  private final CardRetentionService retention;

  CardRetentionJob(CardRetentionService retention) {
    this.retention = retention;
  }

  @Scheduled(fixedDelayString = "${foliopay.vault.purge-interval}", initialDelayString = "${foliopay.vault.purge-interval}")
  void run() {
    try {
      retention.purgeExpired();
    } catch (RuntimeException e) {
      log.error("Card purge run failed; the next run carries on", Redacted.of(e));
    }
  }
}
