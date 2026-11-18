package dev.foliopay.gateway.http;

import dev.foliopay.config.WebhookProperties;
import dev.foliopay.gateway.WebhookSender;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Posts one signed event to the integrator. Short timeouts: a slow receiver delays only its own retry, and the relay moves on. The
 * receiver's body is never read or stored (it is outside data, and we only need the status).
 */
@Component
class HttpWebhookSender implements WebhookSender {
  private static final int CONNECT_MILLIS = 2_000;
  private static final int READ_MILLIS = 5_000;
  private final RestClient http;
  private final String url;

  HttpWebhookSender(WebhookProperties settings, RestClient.Builder builder) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(CONNECT_MILLIS);
    factory.setReadTimeout(READ_MILLIS);
    this.http = builder.requestFactory(factory).build();
    this.url = settings.url();
  }

  @Override
  public int send(String eventId, long timestampSeconds, String body, String signature) {
    return http.post().uri(url).contentType(MediaType.APPLICATION_JSON)
        .header("Webhook-Id", eventId).header("Webhook-Timestamp", Long.toString(timestampSeconds)).header("Webhook-Signature", signature)
        .body(body).exchange((request, response) -> response.getStatusCode().value());
  }
}
