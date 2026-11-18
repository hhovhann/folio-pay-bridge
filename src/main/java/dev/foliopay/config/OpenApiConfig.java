package dev.foliopay.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The API description. Served only when springdoc is enabled (demo profiles); the committed copy is docs/openapi.json. */
@Configuration
class OpenApiConfig {
  @Bean
  OpenAPI folioPayBridgeApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Folio Pay Bridge API")
                .version("v1")
                .description(
                    "Tokenise cards, charge them once per idempotency key, post the payment to the guest folio in OPERA, turn booking events "
                        + "and app requests into payment links, announce outcomes by signed webhook, and reconcile against the acquirer's "
                        + "settlement. Card numbers are accepted only by POST /api/v1/cards and are never returned. Money is an integer in "
                        + "minor units plus an ISO 4217 currency. Errors are RFC 9457 problem details with a stable code. The version is in "
                        + "the path; a version only grows (new endpoints, optional fields, enum values), and a retired endpoint carries "
                        + "Deprecation and Sunset headers before it goes."));
  }
}
