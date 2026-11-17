package dev.foliopay.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import dev.foliopay.domain.model.Money;
import dev.foliopay.gateway.OperaClient;
import dev.foliopay.gateway.OperaRejectedException;
import dev.foliopay.gateway.OperaUnavailableException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OPERA Cloud over OHIP-style REST. Each failure is mapped to what the posting service needs to decide: a 4xx that will not change on a
 * retry is a rejection (dead-letter, no replay); an outage, a timeout, a rate limit or a lock conflict is "unavailable" (retry, then look
 * before posting again). An expired access token is replaced once, transparently. Wired only when foliopay.gateway=http.
 */
@Component
@ConditionalOnProperty(name = "foliopay.gateway", havingValue = "http")
public class HttpOperaClient implements OperaClient {
  private static final Logger log = LoggerFactory.getLogger(HttpOperaClient.class);
  private static final long EXPIRY_MARGIN_SECONDS = 10;
  private final RestClient http;
  private final HttpGatewayProperties settings;
  private String accessToken;
  private Instant tokenExpiresAt = Instant.EPOCH;

  HttpOperaClient(HttpGatewayProperties settings, RestClient.Builder builder) {
    this.settings = settings;
    this.http = builder.baseUrl(settings.operaBaseUrl()).requestFactory(HttpClients.factory(settings)).build();
  }

  @Override
  public String postPayment(OperaPayment p) {
    Map<String, Object> body = Map.of(
        "folioWindow", p.folioWindow(), "paymentCode", p.paymentCode(), "cashierId", p.cashierId(), "amount", p.amount().toDecimalString(),
        "currency", p.amount().currency(), "businessDate", p.businessDate().toString(), "reference", p.externalRef(), "pspRef", String.valueOf(p.pspRef()));
    JsonNode reply = call(p.hotelId(), c -> c.post().uri("/v1/hotels/{h}/reservations/{r}/payments", p.hotelId(), p.reservationId()).contentType(MediaType.APPLICATION_JSON).body(body));
    return reply.path("transactionNo").asText();
  }

  @Override
  public Optional<Posted> find(String hotelId, String externalRef) {
    JsonNode reply = call(hotelId, c -> c.get().uri("/v1/hotels/{h}/payments?reference={ref}", hotelId, externalRef));
    for (JsonNode payment : reply.path("payments")) {
      Money amount = new Money(toMinor(payment.path("amount").asText(), payment.path("currency").asText()), payment.path("currency").asText());
      return Optional.of(new Posted(payment.path("transactionNo").asText(), payment.path("reservationId").asText(), amount, LocalDate.parse(payment.path("businessDate").asText())));
    }
    return Optional.empty();
  }

  @Override
  public LocalDate businessDate(String hotelId) {
    return LocalDate.parse(call(hotelId, c -> c.get().uri("/v1/hotels/{h}/business-date", hotelId)).path("businessDate").asText());
  }

  private JsonNode call(String hotelId, java.util.function.Function<RestClient, RestClient.RequestHeadersSpec<?>> request) {
    try {
      return send(hotelId, request, false);
    } catch (TokenExpired expired) {
      return send(hotelId, request, true);
    }
  }

  private JsonNode send(String hotelId, java.util.function.Function<RestClient, RestClient.RequestHeadersSpec<?>> request, boolean freshToken) {
    try {
      JsonNode reply = request.apply(http).header("Authorization", "Bearer " + token(freshToken)).header("x-hotelid", hotelId).header("x-app-key", settings.operaAppKey())
          .retrieve().body(JsonNode.class);
      return reply == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : reply;
    } catch (HttpClientErrorException e) {
      throw map(e.getStatusCode(), e.getResponseBodyAsString(), freshToken);
    } catch (HttpServerErrorException e) {
      throw new OperaUnavailableException("OPERA answered " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw new OperaUnavailableException("OPERA did not answer in time"); // connection refused or read timeout: it may or may not have arrived
    }
  }

  private RuntimeException map(HttpStatusCode status, String body, boolean alreadyRefreshed) {
    int code = status.value();
    if (code == 401 && !alreadyRefreshed) return new TokenExpired();
    if (code == 429 || code == 409 || code == 401) return new OperaUnavailableException("OPERA asked us to wait (" + code + ")");
    return new OperaRejectedException("OPERA refused the request (" + code + "): " + reason(body));
  }

  private static String reason(String body) {
    int at = body == null ? -1 : body.indexOf("\"code\":\"");
    return at < 0 ? "no reason given" : body.substring(at + 8, Math.min(body.length(), body.indexOf('"', at + 8)));
  }

  private synchronized String token(boolean force) {
    if (!force && accessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(EXPIRY_MARGIN_SECONDS))) return accessToken;
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", settings.operaClientId());
    form.add("client_secret", settings.operaClientSecret());
    try {
      JsonNode reply = http.post().uri("/oauth/v1/tokens").header("x-app-key", settings.operaAppKey()).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
      accessToken = reply.path("access_token").asText();
      tokenExpiresAt = Instant.now().plusSeconds(reply.path("expires_in").asLong(60));
      log.info("Fetched a new OPERA access token, valid for {} s", reply.path("expires_in").asLong(60));
      return accessToken;
    } catch (HttpClientErrorException e) {
      throw new OperaRejectedException("OPERA refused our credentials (" + e.getStatusCode().value() + "): check the client id, secret and application key");
    } catch (RestClientException e) {
      throw new OperaUnavailableException("OPERA token service did not answer");
    }
  }

  private static long toMinor(String decimal, String currency) {
    int digits = Money.fractionDigits(currency);
    String[] parts = decimal.split("\\.");
    String fraction = parts.length > 1 ? parts[1] : "";
    return Long.parseLong(parts[0] + (fraction + "0".repeat(digits)).substring(0, digits));
  }

  private static final class TokenExpired extends RuntimeException {
    TokenExpired() { super("token expired", null, false, false); }
  }
}
