package dev.foliopay.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.SettlementLine;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.gateway.PspDeclinedException;
import dev.foliopay.gateway.PspRejectedException;
import dev.foliopay.gateway.PspTransientException;
import dev.foliopay.gateway.PspUnreadableReplyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The card processor over REST. Every money call carries the Idempotency-Key, so a retry after a lost response returns the first result
 * instead of charging again. A 402 is a decline (final). A timeout, a connection error, a 5xx, a 408, a 429, a 404 that is not the
 * processor's own answer (no error code: a wrong address or a proxy), or a 409 for a request with the same key still in progress is
 * transient (retry with the same key). A success without a reference is unreadable: the charge stays pending with its hold. So are 401 and 403: our credentials were refused (a rotated key, a proxy),
 * which says nothing about the guest's card, so the charge must stay retryable rather than be declined for good. A 409
 * {@code IDEMPOTENCY_KEY_REUSED} and every other 4xx are a rejection of the request itself: final, never retried. The card number is in the request
 * body only for the authorisation, and is never part of an exception message. Wired only when foliopay.gateway=http.
 */
@Component
@ConditionalOnProperty(name = "foliopay.gateway", havingValue = "http")
public class HttpPspClient implements PspClient {
  static final String KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
  private final RestClient http;
  private final String apiKey;

  HttpPspClient(HttpGatewayProperties settings, RestClient.Builder builder) {
    settings.requirePsp();
    this.apiKey = settings.pspApiKey();
    this.http = builder.baseUrl(settings.pspBaseUrl()).requestFactory(HttpClients.factory(settings)).build();
  }

  @Override
  public PspResult authorize(String idempotencyKey, Pan pan, String expiry, Money amount) {
    Map<String, Object> body = Map.of("pan", pan.value(), "expiry", expiry, "amountMinor", amount.minor(), "currency", amount.currency());
    return new PspResult(reference(post("/v1/authorizations", idempotencyKey, body), "authorisation"));
  }

  @Override
  public PspResult capture(String idempotencyKey, String authRef, Money amount) {
    Map<String, Object> body = Map.of("authRef", authRef, "amountMinor", amount.minor(), "currency", amount.currency());
    return new PspResult(reference(post("/v1/captures", idempotencyKey, body), "capture"));
  }

  /**
   * A success without a reference cannot be used: capturing or voiding a blank authorisation, or recording a capture reconciliation can
   * never match, would turn "we do not know" into a wrong answer. The step may have happened, so the charge stays PENDING (D-34).
   */
  private static String reference(JsonNode reply, String step) {
    String ref = reply == null ? "" : reply.path("ref").asText();
    if (ref.isBlank()) {
      throw new PspUnreadableReplyException("The processor accepted the " + step + " but sent no reference");
    }
    return ref;
  }

  @Override
  public void voidAuthorization(String idempotencyKey, String authRef) {
    post("/v1/voids", idempotencyKey, Map.of("authRef", authRef));
  }

  @Override
  public List<SettlementLine> settlementStatement() {
    JsonNode reply = guarded(() -> http.get().uri("/v1/settlements").header("Authorization", "Bearer " + apiKey).retrieve().body(JsonNode.class));
    List<SettlementLine> lines = new ArrayList<>();
    reply.path("lines").forEach(l -> lines.add(new SettlementLine(l.path("pspRef").asText(), l.path("amountMinor").asLong(), l.path("currency").asText())));
    return lines;
  }

  private JsonNode post(String path, String idempotencyKey, Map<String, Object> body) {
    return guarded(() -> http.post().uri(path).header("Authorization", "Bearer " + apiKey).header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));
  }

  private static JsonNode guarded(Supplier<JsonNode> call) {
    try {
      return call.get();
    } catch (HttpClientErrorException e) {
      int code = e.getStatusCode().value();
      String reason = ErrorBodies.code(e.getResponseBodyAsString());
      if (code == 402) {
        throw new PspDeclinedException("Declined by the processor");
      }
      if (code == 401 || code == 403) {
        throw new PspTransientException("The processor refused our credentials (" + code + "): check the API key"); // ours to fix, not the guest's
      }
      if (code == 408 || code == 429 || (code == 409 && !KEY_REUSED.equals(reason))) {
        // A request timeout, a rate limit, or the same key still being processed by a parallel request: wait and retry with the same key.
        throw new PspTransientException("The processor asked us to wait (" + code + ")");
      }
      if (code == 404 && ErrorBodies.NO_REASON.equals(reason)) {
        // Not the processor's own answer (that carries an error code) but a wrong address or a proxy: ours to fix, not the guest's card.
        throw new PspTransientException("The processor could not be reached at the configured address (404)");
      }
      throw new PspRejectedException("The processor refused the request (" + code + "): " + reason); // retrying cannot change it
    } catch (HttpServerErrorException e) {
      throw new PspTransientException("The processor answered " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw new PspTransientException("The processor did not answer in time"); // it may or may not have captured: retry with the same key
    }
  }
}
