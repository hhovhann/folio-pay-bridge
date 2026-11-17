package dev.foliopay.gateway.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Where the real (or simulated) PMS and processor live, and how patient to be with them. Credentials come from the environment, never from the repo. */
@ConfigurationProperties("foliopay.http")
public record HttpGatewayProperties(
    @DefaultValue("http://localhost:8090") String operaBaseUrl,
    @DefaultValue("") String operaAppKey,
    @DefaultValue("") String operaClientId,
    @DefaultValue("") String operaClientSecret,
    @DefaultValue("http://localhost:8091") String pspBaseUrl,
    @DefaultValue("") String pspApiKey,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("3s") Duration readTimeout) {
  @Override
  public String toString() { return "HttpGatewayProperties[redacted]"; } // holds credentials
}
