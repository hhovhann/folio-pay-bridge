package dev.foliopay.config;

import dev.foliopay.logging.Redacted;
import dev.foliopay.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the webhook relay every {@code foliopay.webhook.poll} (5 s) when a webhook URL is configured: queue new events, then send what is
 * due. Safe on every instance at once: queueing is idempotent and each send is leased. A failed run is logged and the next one carries on.
 */
@Configuration
@EnableScheduling
@ConditionalOnExpression("!'${foliopay.webhook.url:}'.isBlank()")
class WebhookRelay {
  private static final Logger log = LoggerFactory.getLogger(WebhookRelay.class);
  private final NotificationService notifications;

  WebhookRelay(NotificationService notifications, WebhookProperties settings) {
    settings.requireUsable();
    this.notifications = notifications;
  }

  @Scheduled(fixedDelayString = "${foliopay.webhook.poll:5s}", initialDelayString = "${foliopay.webhook.poll:5s}")
  void run() {
    try {
      notifications.collect();
      notifications.deliverDue();
    } catch (RuntimeException e) {
      log.error("Webhook relay run failed; the next run carries on", Redacted.of(e));
    }
  }
}
