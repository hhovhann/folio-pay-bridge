package dev.foliopay.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationTest {
  private static final Instant NOW = Instant.parse("2026-11-18T08:00:00Z");

  @Test
  void theEventIdIsDeterministicSoTheSameEventIsNeverQueuedTwice() {
    Notification n = new Notification(Notification.Type.PAYMENT_CAPTURED, "ch-1", "{}", NOW);
    assertThat(n.getEventId()).isEqualTo("payment.captured:ch-1");
    assertThat(n.getNextAttemptAt()).isEqualTo(NOW);
  }

  @Test
  void aFailureWaitsTwiceAsLongEachTimeUpToAnHourAndTheLastAttemptEndsFailedNotDropped() {
    Notification n = new Notification(Notification.Type.FOLIO_POSTED, "p-1", "{}", NOW);
    n.failed("The receiver answered 500", NOW, 12);
    assertThat(Duration.between(NOW, n.getNextAttemptAt())).isEqualTo(Duration.ofSeconds(30));
    n.failed("The receiver answered 500", NOW, 12);
    assertThat(Duration.between(NOW, n.getNextAttemptAt())).isEqualTo(Duration.ofMinutes(1));
    for (int i = 0; i < 8; i++) n.failed("down", NOW, 12);
    assertThat(Duration.between(NOW, n.getNextAttemptAt())).isEqualTo(Duration.ofHours(1));
    assertThat(n.getStatus()).isEqualTo(Notification.Status.PENDING);

    n.failed("down", NOW, 12);
    n.failed("down", NOW, 12);

    assertThat(n.getStatus()).isEqualTo(Notification.Status.FAILED);
    assertThat(n.getAttempts()).isEqualTo(12);
    assertThat(n.getLastError()).isEqualTo("down");
  }

  @Test
  void aFailedEventCanBeSentAgainFromTheStartButADeliveredOneIsLeftAlone() {
    Notification failed = new Notification(Notification.Type.FOLIO_POSTED, "p-1", "{}", NOW);
    failed.failed("down", NOW, 1);
    failed.redeliver(NOW.plusSeconds(60));
    assertThat(failed.getStatus()).isEqualTo(Notification.Status.PENDING);
    assertThat(failed.getAttempts()).isZero();
    assertThat(failed.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));

    Notification delivered = new Notification(Notification.Type.FOLIO_POSTED, "p-2", "{}", NOW);
    delivered.delivered(NOW);
    delivered.redeliver(NOW.plusSeconds(60));
    assertThat(delivered.getStatus()).isEqualTo(Notification.Status.DELIVERED);
    assertThat(delivered.getDeliveredAt()).isEqualTo(NOW);
  }
}
