package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.foliopay.service.NotificationService;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("Webhook relay: the schedule, its failures and a weak secret")
class WebhookRelayTest {
  private static final WebhookProperties SETTINGS = new WebhookProperties("https://hooks.example.test/x", "s".repeat(32), 12, Duration.ofSeconds(5), false, Duration.ofDays(30));
  private final NotificationService notifications = mock(NotificationService.class);

  @Test
  @DisplayName("Each relay run queues new events, then sends what is due")
  void eachRunQueuesNewEventsThenSendsWhatIsDue() {
    new WebhookRelay(notifications, SETTINGS).run();
    InOrder order = inOrder(notifications);
    order.verify(notifications).collect();
    order.verify(notifications).deliverDue();
  }

  /** Fails if one bad run (the database briefly away) kills the schedule: no event would ever be sent again until a restart. */
  @Test
  @DisplayName("A failed relay run is logged and does not stop the schedule")
  void aFailedRunIsLoggedAndDoesNotStopTheSchedule() {
    when(notifications.collect()).thenThrow(new IllegalStateException("database unavailable"));
    assertThatCode(() -> new WebhookRelay(notifications, SETTINGS).run()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("The relay refuses to start with a weak secret")
  void theRelayRefusesToStartWithAWeakSecret() {
    WebhookProperties weak = new WebhookProperties("https://hooks.example.test/x", "short", 12, Duration.ofSeconds(5), false, Duration.ofDays(30));
    assertThatThrownBy(() -> new WebhookRelay(notifications, weak)).isInstanceOf(IllegalStateException.class);
  }
}
