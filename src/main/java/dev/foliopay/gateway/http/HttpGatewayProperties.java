package dev.foliopay.gateway.http;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the real (or simulated) PMS and processor live, and how patient to be with them. Credentials come from the environment, never from
 * the repo. There are no default addresses or credentials: in HTTP mode a missing one stops start-up (D-17) instead of quietly talking to
 * whatever listens on localhost.
 */
@ConfigurationProperties("foliopay.http")
public record HttpGatewayProperties(
    String operaBaseUrl,
    String operaAppKey,
    String operaClientId,
    String operaClientSecret,
    String pspBaseUrl,
    String pspApiKey,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("3s") Duration readTimeout) {
  @Override
  public String toString() { return "HttpGatewayProperties[redacted]"; } // holds credentials

  void requireOpera() {
    Map<String, String> needed = new LinkedHashMap<>();
    needed.put("foliopay.http.opera-base-url", operaBaseUrl);
    needed.put("foliopay.http.opera-app-key", operaAppKey);
    needed.put("foliopay.http.opera-client-id", operaClientId);
    needed.put("foliopay.http.opera-client-secret", operaClientSecret);
    require(needed);
  }

  void requirePsp() {
    Map<String, String> needed = new LinkedHashMap<>();
    needed.put("foliopay.http.psp-base-url", pspBaseUrl);
    needed.put("foliopay.http.psp-api-key", pspApiKey);
    require(needed);
  }

  private static void require(Map<String, String> needed) {
    needed.forEach((name, value) -> {
      if (value == null || value.isBlank()) {
        throw new IllegalStateException(name + " is not set: foliopay.gateway=http needs every address and credential, there are no defaults");
      }
    });
  }
}
