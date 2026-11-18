package dev.foliopay.gateway;

/** Port to the integrator's webhook endpoint. Returns the HTTP status it answered; throws if it did not answer at all. */
public interface WebhookSender {
  int send(String eventId, long timestampSeconds, String body, String signature);
}
