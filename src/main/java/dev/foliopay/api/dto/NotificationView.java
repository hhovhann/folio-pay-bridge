package dev.foliopay.api.dto;

import dev.foliopay.domain.model.Notification;
import java.time.Instant;

/** What an operator sees of a webhook event: its state and the payload that was (or will be) sent, which holds no card data. */
public record NotificationView(String id, String eventId, String type, String status, int attempts, Instant nextAttemptAt, Instant deliveredAt,
    String lastError, String payload) {
  public static NotificationView from(Notification n) {
    return new NotificationView(n.getId(), n.getEventId(), n.getType().wireName(), n.getStatus()
        .name(), n.getAttempts(), n.getNextAttemptAt(), n.getDeliveredAt(), n.getLastError(), n.getPayload());
  }
}
