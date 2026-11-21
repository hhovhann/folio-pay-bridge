package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The published API description (docs/openapi.json) must be what the code really serves. After an intentional API change regenerate it:
 * {@code UPDATE_OPENAPI=true ./mvnw test -Dtest=OpenApiContractTest}.
 */
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("OpenAPI: the published description is what the code serves")
class OpenApiContractTest {
  private static final Path COMMITTED = Path.of("docs", "openapi.json");
  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  @Autowired MockMvc mvc;

  private JsonNode served() throws Exception {
    String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return mapper.readTree(body);
  }

  @Test
  @DisplayName("The committed OpenAPI file matches what the code serves")
  void theCommittedSpecMatchesWhatTheCodeServes() throws Exception {
    JsonNode live = served();
    if ("true".equals(System.getenv("UPDATE_OPENAPI"))) {
      Files.writeString(COMMITTED, mapper.writeValueAsString(live) + "\n");
    }
    assertThat(Files.exists(COMMITTED)).as("docs/openapi.json is committed").isTrue();
    assertThat(live).as("docs/openapi.json is stale: regenerate with UPDATE_OPENAPI=true").isEqualTo(mapper.readTree(Files.readString(COMMITTED)));
  }

  @Test
  @DisplayName("A card number is accepted by exactly one operation")
  void aCardNumberIsAcceptedByExactlyOneOperation() throws Exception {
    JsonNode doc = served();
    List<String> acceptingPan = new ArrayList<>();
    doc.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(op -> {
      String schemaRef = op.getValue().path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText("");
      JsonNode schema = doc.path("components").path("schemas").path(schemaRef.substring(schemaRef.lastIndexOf('/') + 1));
      if (schema.path("properties").has("pan")) {
        acceptingPan.add(op.getKey().toUpperCase() + " " + path.getKey());
      }
    }));
    assertThat(acceptingPan).containsExactly("POST /api/v1/cards");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"CardResponse", "ChargeView", "PostingView", "PaymentResponse", "LinkView"})
  @DisplayName("No response schema carries a card number or the card token")
  void noResponseSchemaCarriesACardNumberOrTheCardToken(String name) throws Exception {
    JsonNode schema = served().path("components").path("schemas").path(name);
    assertThat(schema.isMissingNode()).as("%s is documented", name).isFalse();
    Iterator<String> fields = schema.path("properties").fieldNames();
    while (fields.hasNext()) {
      assertThat(fields.next().toLowerCase()).isNotIn("pan", "cardnumber", "cardtoken", "cvv");
    }
  }

  @Test
  @DisplayName("Payment operations are documented")
  void paymentOperationsAreDocumented() throws Exception {
    JsonNode paths = served().path("paths");
    assertThat(paths.fieldNames()).toIterable()
        .contains("/api/v1/cards", "/api/v1/payments", "/api/v1/postings/{id}/replay", "/api/v1/booking-events", "/api/v1/links/{id}/pay",
            "/api/v1/reconcile");
    assertThat(paths.path("/api/v1/payments").path("post").path("description").asText()).contains("never charges twice");
  }
}
