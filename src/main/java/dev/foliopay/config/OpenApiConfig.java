package dev.foliopay.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The API description. Served only when springdoc is enabled (demo profile); the committed copy is docs/openapi.json. */
@Configuration
class OpenApiConfig {
  @Bean
  OpenAPI folioPayBridgeApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Folio Pay Bridge API")
                .version("0.1.0")
                .description(
                    "Concept pilot: tokenise virtual cards, charge them once per idempotency key, post to the guest folio, "
                        + "turn booking events into payment links, and reconcile against an acquirer statement. "
                        + "Synthetic data only: the processor and the PMS are in-memory fakes. Card numbers are accepted only by POST /api/cards "
                        + "and are never returned. Money is an integer in minor units (cents) plus an ISO 4217 currency.")
                .license(new License().name("Concept pilot, not for production use")));
  }
}
