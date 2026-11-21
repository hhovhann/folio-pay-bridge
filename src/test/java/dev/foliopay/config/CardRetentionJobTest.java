package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.foliopay.service.CardRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("Card purge schedule: each run purges, and a failed run neither stops the schedule nor logs what it held")
class CardRetentionJobTest {
  private final CardRetentionService retention = mock(CardRetentionService.class);

  @Test
  @DisplayName("Each run purges the expired cards")
  void eachRunPurgesTheExpiredCards() {
    new CardRetentionJob(retention).run();

    verify(retention).purgeExpired();
  }

  /** Fails if one bad run (the database briefly away) kills the schedule, or logs the failure's message, which can carry what it held. */
  @Test
  @DisplayName("A failed run is logged without its message and does not stop the schedule")
  void aFailedRunIsLoggedWithoutItsMessageAndDoesNotStopTheSchedule() {
    when(retention.purgeExpired()).thenThrow(new IllegalStateException("tok_" + "0123456789abcdef".repeat(2)));
    Logger logger = (Logger) LoggerFactory.getLogger(CardRetentionJob.class);
    ListAppender<ILoggingEvent> lines = new ListAppender<>();
    lines.start();
    logger.addAppender(lines);
    try {
      assertThatCode(() -> new CardRetentionJob(retention).run()).doesNotThrowAnyException();

      assertThat(lines.list).singleElement().satisfies(line -> {
        assertThat(line.getFormattedMessage()).contains("Card purge run failed");
        assertThat(line.getThrowableProxy().getMessage()).doesNotContain("tok_");
      });
    } finally {
      logger.detachAppender(lines);
    }
  }
}
