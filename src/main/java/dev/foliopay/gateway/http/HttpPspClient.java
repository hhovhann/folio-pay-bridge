package dev.foliopay.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.SettlementLine;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.gateway.PspDeclinedException;
import dev.foliopay.gateway.PspTransientException;
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
 * instead of charging again. A 402 is a decline (final); a timeout, a connection error or a 5xx is transient (retry with the same key). The
 * card number is in the request body only for the authorisation, and is never part of an exception message. Wired only when foliopay.gateway=http.
 */
@Component
@ConditionalOnProperty(name = "foliopay.gateway", havingValue = "http")
public class HttpPspClient implements PspClient {
  private final RestClient http;
  private final String apiKey;

  HttpPspClient(HttpGatewayProperties settings, RestClient.Builder builder) {
    this.apiKey = settings.pspApiKey();
    this.http = builder.baseUrl(settings.pspBaseUrl()).requestFactory(HttpClients.factory(settings)).build();
  }

  @Override
  public PspResult authorize(String idempotencyKey, Pan pan, String expiry, Money amount) {
    Map<String, Object> body = Map.of("pan", pan.value(), "expiry", expiry, "amountMinor", amount.minor(), "currency", amount.currency());
    return new PspResult(post("/v1/authorizations", idempotencyKey, body).path("ref").asText());
  }

  @Override
  public PspResult capture(String idempotencyKey, String authRef, Money amount) {
    Map<String, Object> body = Map.of("authRef", authRef, "amountMinor", amount.minor(), "currency", amount.currency());
    return new PspResult(post("/v1/captures", idempotencyKey, body).path("ref").asText());
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
      if (e.getStatusCode().value() == 402) {
        throw new PspDeclinedException("Declined by the processor");
      }
      throw new IllegalStateException("The processor refused our request (" + e.getStatusCode().value() + ")"); // our bug or our credentials: not a retry
    } catch (HttpServerErrorException e) {
      throw new PspTransientException("The processor answered " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw new PspTransientException("The processor did not answer in time"); // it may or may not have captured: retry with the same key
    }
  }
}
