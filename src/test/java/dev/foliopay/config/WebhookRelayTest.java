package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.foliopay.service.NotificationService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WebhookRelayTest {
  private static final WebhookProperties SETTINGS = new WebhookProperties("https://hooks.example.test/x", "s".repeat(32), 12, Duration.ofSeconds(5), false, Duration.ofDays(30));
  private final NotificationService notifications = mock(NotificationService.class);

  @Test
  void eachRunQueuesNewEventsThenSendsWhatIsDue() {
    new WebhookRelay(notifications, SETTINGS).run();
    InOrder order = inOrder(notifications);
    order.verify(notifications).collect();
    order.verify(notifications).deliverDue();
  }

  /** Fails if one bad run (the database briefly away) kills the schedule: no event would ever be sent again until a restart. */
  @Test
  void aFailedRunIsLoggedAndDoesNotStopTheSchedule() {
    when(notifications.collect()).thenThrow(new IllegalStateException("database unavailable"));
    assertThatCode(() -> new WebhookRelay(notifications, SETTINGS).run()).doesNotThrowAnyException();
  }

  @Test
  void theRelayRefusesToStartWithAWeakSecret() {
    WebhookProperties weak = new WebhookProperties("https://hooks.example.test/x", "short", 12, Duration.ofSeconds(5), false, Duration.ofDays(30));
    assertThatThrownBy(() -> new WebhookRelay(notifications, weak)).isInstanceOf(IllegalStateException.class);
  }
}
