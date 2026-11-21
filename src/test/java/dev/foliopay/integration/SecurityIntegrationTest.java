package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import dev.foliopay.service.support.Fingerprint;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Authentication and authorisation end to end (D-30), with nothing mocked in between: a real RSA key signs the tokens, a local HTTP server
 * publishes its JWKS the way Cognito or Okta would, and Spring Security fetches it and checks signature, issuer, audience, expiry and scope.
 */
// The test's own signing-key server listens on loopback over plain HTTP, so plaintext is allowed by name, as in the Compose stack (D-45).
@SpringBootTest(properties = {"foliopay.demo-controls=false", "foliopay.allow-plaintext-transport=true"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Authentication and authorisation end to end (D-30)")
class SecurityIntegrationTest {
  private static final String ISSUER = "https://auth.example.test/tenant-1";
  private static final String AUDIENCE = "folio-pay-bridge";
  private static final String PREFIX = "https://api.example.test/";
  private static final String READ_KEY = "fpb_test_read0000000000000000000000000";
  private static final String SPA_KEY = "fpb_test_spa00000000000000000000000000";
  private static final RSAKey SIGNING = generate("k1");
  private static final RSAKey STRANGER = generate("k1"); // same key id, different key: a forged token
  private static final HttpServer JWKS = jwks();
  /** The scope each operation needs, as SecurityConfig grants it (D-37), written out so that a change to either shows up here. */
  private static final Map<String, String> SCOPE_OF = Map.ofEntries(
      Map.entry("POST /api/v1/cards", "cards:write"),
      Map.entry("POST /api/v1/payments", "payments:write"), Map.entry("POST /api/v1/links/{id}/pay", "payments:write"),
      Map.entry("POST /api/v1/links", "links:write"),
      Map.entry("POST /api/v1/booking-events", "bookings:write"),
      Map.entry("GET /api/v1/charges", "payments:read"), Map.entry("GET /api/v1/postings", "payments:read"),
      Map.entry("GET /api/v1/links", "payments:read"), Map.entry("GET /api/v1/notifications", "payments:read"),
      Map.entry("GET /api/v1/overview", "payments:read"),
      Map.entry("POST /api/v1/postings/{id}/replay", "operations"), Map.entry("POST /api/v1/notifications/{id}/redeliver", "operations"),
      Map.entry("POST /api/v1/reconcile", "operations"), Map.entry("POST /api/v1/shadow", "operations"),
      Map.entry("GET /api/v1/logs", "operations"), Map.entry("GET /api/v1/vault", "operations"),
      Map.entry("GET /api/v1/sim", "operations"), Map.entry("POST /api/v1/sim", "operations"));

  @Autowired private MockMvc mvc;

  private static RSAKey generate(String kid) {
    try {
      return new RSAKeyGenerator(2048).keyID(kid).generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpServer jwks() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] body = new JWKSet(SIGNING.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      server.createContext("/.well-known/jwks.json", exchange -> {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      server.start();
      return server;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void auth(DynamicPropertyRegistry registry) {
    registry.add("foliopay.auth.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/.well-known/jwks.json");
    registry.add("foliopay.auth.jwt.issuer", () -> ISSUER);
    registry.add("foliopay.auth.jwt.audience", () -> AUDIENCE);
    registry.add("foliopay.auth.jwt.scope-prefix", () -> PREFIX);
    registry.add("foliopay.auth.api-keys", () -> "finance-report:" + HexFormat.of().formatHex(Fingerprint.sha256(READ_KEY)) + ":payments:read,"
        + "spa-app:" + HexFormat.of().formatHex(Fingerprint.sha256(SPA_KEY)) + ":payments:write");
  }

  @AfterAll
  static void stop() { JWKS.stop(0); }

  private static String token(RSAKey key, String issuer, String audience, Instant expires, String scope) throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).audience(List.of(audience)).subject("pms-middleware")
        .claim("client_id", "pms-middleware").claim("scope", scope).issueTime(new Date()).expirationTime(Date.from(expires)).build();
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static String valid(String scope) throws Exception {
    return token(SIGNING, ISSUER, AUDIENCE, Instant.now().plusSeconds(300), scope);
  }

  private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }

  @Test
  @DisplayName("With no credentials, the API is refused but health and the console page are not")
  void withNoCredentialsTheApiIsRefusedButHealthAndTheConsolePageAreNot() throws Exception {
    mvc.perform(get("/api/v1/charges")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(header().string("WWW-Authenticate", "Bearer"));
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/index.html")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("A valid token with the right scope is let in, and the authorization server's scope prefix is understood")
  void aValidTokenWithTheRightScopeIsLetInAndTheAuthorizationServersScopePrefixIsUnderstood() throws Exception {
    mvc.perform(bearer(get("/api/v1/charges"), valid(PREFIX + "payments:read"))).andExpect(status().isOk());
  }

  /** Fails if any one of the token checks is missing: each of these tokens is wrong in exactly one way. */
  @Test
  @DisplayName("A token that is expired, for another API, from another issuer or forged is refused")
  void aTokenThatIsExpiredForAnotherApiFromAnotherIssuerOrForgedIsRefused() throws Exception {
    String scope = PREFIX + "payments:read";
    for (String bad : List.of(
        token(SIGNING, ISSUER, AUDIENCE, Instant.now().minusSeconds(120), scope),
        token(SIGNING, ISSUER, "another-api", Instant.now().plusSeconds(300), scope),
        token(SIGNING, "https://evil.example.test", AUDIENCE, Instant.now().plusSeconds(300), scope),
        token(STRANGER, ISSUER, AUDIENCE, Instant.now().plusSeconds(300), scope),
        "not.a.jwt")) {
      mvc.perform(bearer(get("/api/v1/charges"), bad)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
  }

  /** Fails if a client can do more than its scopes say: a reporting client must never move money or replay a posting. */
  @Test
  @DisplayName("A client can do only what its scopes allow")
  void aClientCanDoOnlyWhatItsScopesAllow() throws Exception {
    String readOnly = valid(PREFIX + "payments:read");
    mvc.perform(bearer(post("/api/v1/payments"), readOnly).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(bearer(post("/api/v1/cards"), valid(PREFIX + "payments:write")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(bearer(post("/api/v1/postings/p-1/replay"), valid(PREFIX + "payments:write"))).andExpect(status().isForbidden());
    mvc.perform(bearer(post("/api/v1/reconcile"), valid(PREFIX + "payments:read")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    // allowed: the posting is unknown
    mvc.perform(bearer(post("/api/v1/postings/p-1/replay"), valid(PREFIX + "operations"))).andExpect(status().isNotFound());
  }

  /**
   * Fails if an operation the published API description lists is open without credentials, or to a scope that is not its own: an endpoint
   * that fell through to permitAll, a matcher that lets a reading client write (API5). Fails too if the description has an operation the
   * table above does not name, so no endpoint ships before its scope is chosen. Only refusals are sent: nothing reaches a controller.
   */
  @Test
  @DisplayName("Every operation the API describes is refused without credentials and to every scope but its own")
  void everyOperationTheApiDescribesIsRefusedWithoutCredentialsAndToEveryScopeButItsOwn() throws Exception {
    Set<String> described = new TreeSet<>();
    JsonNode paths = new ObjectMapper().readTree(Path.of("docs/openapi.json").toFile()).path("paths");
    paths.fields().forEachRemaining(path -> path.getValue().fieldNames()
        .forEachRemaining(method -> described.add(method.toUpperCase(Locale.ROOT) + " " + path.getKey())));
    described.removeIf(operation -> operation.startsWith("GET /api/v1/public/")); // how to sign in, open by design (test below)
    assertThat(described).as("the operations whose scope this test states").isEqualTo(new TreeSet<>(SCOPE_OF.keySet()));

    List<String> wrong = new ArrayList<>();
    for (String operation : described) {
      HttpMethod method = HttpMethod.valueOf(operation.substring(0, operation.indexOf(' ')));
      String uri = operation.substring(operation.indexOf(' ') + 1).replaceAll("\\{[^}]+}", "x-1");
      int anonymous = mvc.perform(request(method, uri)).andReturn().getResponse().getStatus();
      if (anonymous != 401) {
        wrong.add(operation + " without credentials: " + anonymous);
      }
      for (String scope : List.of("cards:write", "payments:write", "links:write", "bookings:write", "payments:read", "operations")) {
        if (scope.equals(SCOPE_OF.get(operation))) {
          continue;
        }
        int status = mvc.perform(bearer(request(method, uri), valid(PREFIX + scope))).andReturn().getResponse().getStatus();
        if (status != 403) {
          wrong.add(operation + " with " + scope + ": " + status);
        }
      }
    }
    assertThat(wrong).as("operations let in without their own scope").isEmpty();
  }

  @Test
  @DisplayName("An API key is checked against its hash and carries its own scopes")
  void anApiKeyIsCheckedAgainstItsHashAndCarriesItsOwnScopes() throws Exception {
    mvc.perform(get("/api/v1/charges").header("X-Api-Key", READ_KEY)).andExpect(status().isOk());
    mvc.perform(post("/api/v1/payments").header("X-Api-Key", READ_KEY).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status()
        .isForbidden());
    mvc.perform(get("/api/v1/charges").header("X-Api-Key", "fpb_test_wrong")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code")
        .value("UNAUTHORIZED"));
  }

  /** Fails if a wrong key is ignored as if absent: a caller holding one valid credential could probe keys without ever being refused. */
  @Test
  @DisplayName("A wrong API key is refused even alongside a valid token")
  void aWrongApiKeyIsRefusedEvenAlongsideAValidToken() throws Exception {
    mvc.perform(bearer(get("/api/v1/charges"), valid(PREFIX + "payments:read")).header("X-Api-Key", "fpb_test_wrong"))
        .andExpect(status().isUnauthorized());
  }

  /** The dashboard learns how to sign in without credentials; the overview it then reads needs payments:read like any ledger. */
  @Test
  @DisplayName("The dashboard's sign-in settings are public, but its data is not")
  void theDashboardsSignInSettingsArePublicButItsDataIsNot() throws Exception {
    mvc.perform(get("/api/v1/public/dashboard-config")).andExpect(status().isOk()).andExpect(jsonPath("$.auth").value("unavailable"));
    mvc.perform(get("/api/v1/overview")).andExpect(status().isUnauthorized());
    mvc.perform(bearer(get("/api/v1/overview"), valid(PREFIX + "payments:read"))).andExpect(status().isOk());
    mvc.perform(bearer(get("/api/v1/overview"), valid(PREFIX + "payments:write"))).andExpect(status().isForbidden());
  }

  /** Fails if the scope that lets an app ask for money also lets it take money: a spa app holding a card token could charge any folio. */
  @Test
  @DisplayName("An app that may ask for money cannot take it, and a payer cannot create requests")
  void anAppThatMayAskForMoneyCannotTakeItAndAPayerCannotCreateRequests() throws Exception {
    String request = "{\"reference\":\"SPA-1\",\"folioId\":\"RES-1\",\"amountMinor\":5000,\"currency\":\"USD\",\"dueDate\":\"2099-01-01\"}";
    mvc.perform(bearer(post("/api/v1/links"), valid(PREFIX + "links:write")).contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isOk());
    mvc.perform(bearer(post("/api/v1/payments"), valid(PREFIX + "links:write")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(bearer(post("/api/v1/links/lnk_x/pay"), valid(PREFIX + "links:write")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(bearer(post("/api/v1/links"), valid(PREFIX + "payments:write")).contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isForbidden());
  }

  /**
   * Fails if idempotency keys are shared between clients: the second integrator's valid payment would be refused as a reused key (409),
   * or a client repeating another's key and parameters would be handed the other's charge (D-40).
   */
  @Test
  @DisplayName("Two clients using the same idempotency key get two separate payments")
  void twoClientsUsingTheSameIdempotencyKeyGetTwoSeparatePayments() throws Exception {
    String card = mvc.perform(bearer(post("/api/v1/cards"), valid(PREFIX + "cards:write")).contentType(MediaType.APPLICATION_JSON)
        .content("{\"pan\":\"4111111111111111\",\"expiry\":\"12/29\"}")).andExpect(status().isCreated()).andReturn().getResponse()
            .getContentAsString();
    String token = card.replaceAll(".*\"token\":\"(tok_[a-f0-9]+)\".*", "$1");
    String sameKeyFor = "{\"idempotencyKey\":\"order-1001\",\"bookingRef\":\"BK-1\",\"folioId\":\"F-1\",\"token\":\"" + token
        + "\",\"amountMinor\":%d,\"currency\":\"USD\"}";

    String first = mvc.perform(bearer(post("/api/v1/payments"), valid(PREFIX + "payments:write")).contentType(MediaType.APPLICATION_JSON)
        .content(String.format(sameKeyFor, 10_000))).andExpect(status().isOk()).andExpect(jsonPath("$.charge.amountMinor").value(10_000))
        .andReturn().getResponse().getContentAsString();
    String second = mvc.perform(post("/api/v1/payments").header("X-Api-Key", SPA_KEY).contentType(MediaType.APPLICATION_JSON)
        .content(String.format(sameKeyFor, 25_000))).andExpect(status().isOk()).andExpect(jsonPath("$.charge.amountMinor").value(25_000))
        .andReturn().getResponse().getContentAsString();
    String repeatOfFirstByTheOther = mvc.perform(post("/api/v1/payments").header("X-Api-Key", SPA_KEY).contentType(MediaType.APPLICATION_JSON)
        .content(String.format(sameKeyFor, 10_000))).andReturn().getResponse().getContentAsString();

    assertThat(chargeId(first)).isNotEqualTo(chargeId(second));
    assertThat(repeatOfFirstByTheOther).as("the other client's charge is never handed out").doesNotContain(chargeId(first));
  }

  private static String chargeId(String paymentResponse) {
    return paymentResponse.replaceAll(".*\"charge\":\\{\"id\":\"([^\"]+)\".*", "$1");
  }
}
