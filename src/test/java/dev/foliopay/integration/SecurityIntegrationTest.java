package dev.foliopay.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Authentication and authorisation end to end (D-30), with nothing mocked in between: a real RSA key signs the tokens, a local HTTP server
 * publishes its JWKS the way Cognito or Okta would, and Spring Security fetches it and checks signature, issuer, audience, expiry and scope.
 */
@SpringBootTest(properties = {"foliopay.keys=local", "foliopay.gateway=fake", "foliopay.demo-controls=false"})
@AutoConfigureMockMvc
class SecurityIntegrationTest {
  private static final String ISSUER = "https://auth.example.test/tenant-1";
  private static final String AUDIENCE = "folio-pay-bridge";
  private static final String PREFIX = "https://api.example.test/";
  private static final String READ_KEY = "fpb_test_read0000000000000000000000000";
  private static final RSAKey SIGNING = generate("k1");
  private static final RSAKey STRANGER = generate("k1"); // same key id, different key: a forged token
  private static final HttpServer JWKS = jwks();

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
    registry.add("foliopay.auth.api-keys", () -> "finance-report:" + HexFormat.of().formatHex(Fingerprint.sha256(READ_KEY)) + ":payments:read");
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
  void withNoCredentialsTheApiIsRefusedButHealthAndTheConsolePageAreNot() throws Exception {
    mvc.perform(get("/api/charges")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(header().string("WWW-Authenticate", "Bearer"));
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/index.html")).andExpect(status().isOk());
  }

  @Test
  void aValidTokenWithTheRightScopeIsLetInAndTheAuthorizationServersScopePrefixIsUnderstood() throws Exception {
    mvc.perform(bearer(get("/api/charges"), valid(PREFIX + "payments:read"))).andExpect(status().isOk());
  }

  /** Fails if any one of the token checks is missing: each of these tokens is wrong in exactly one way. */
  @Test
  void aTokenThatIsExpiredForAnotherApiFromAnotherIssuerOrForgedIsRefused() throws Exception {
    String scope = PREFIX + "payments:read";
    for (String bad : List.of(
        token(SIGNING, ISSUER, AUDIENCE, Instant.now().minusSeconds(120), scope),
        token(SIGNING, ISSUER, "another-api", Instant.now().plusSeconds(300), scope),
        token(SIGNING, "https://evil.example.test", AUDIENCE, Instant.now().plusSeconds(300), scope),
        token(STRANGER, ISSUER, AUDIENCE, Instant.now().plusSeconds(300), scope),
        "not.a.jwt")) {
      mvc.perform(bearer(get("/api/charges"), bad)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
  }

  /** Fails if a client can do more than its scopes say: a reporting client must never move money or replay a posting. */
  @Test
  void aClientCanDoOnlyWhatItsScopesAllow() throws Exception {
    String readOnly = valid(PREFIX + "payments:read");
    mvc.perform(bearer(post("/api/payments"), readOnly).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(bearer(post("/api/cards"), valid(PREFIX + "payments:write")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(bearer(post("/api/postings/p-1/replay"), valid(PREFIX + "payments:write"))).andExpect(status().isForbidden());
    mvc.perform(bearer(post("/api/reconcile"), valid(PREFIX + "payments:read")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(bearer(post("/api/postings/p-1/replay"), valid(PREFIX + "operations"))).andExpect(status().isNotFound()); // allowed: the posting is unknown
  }

  @Test
  void anApiKeyIsCheckedAgainstItsHashAndCarriesItsOwnScopes() throws Exception {
    mvc.perform(get("/api/charges").header("X-Api-Key", READ_KEY)).andExpect(status().isOk());
    mvc.perform(post("/api/payments").header("X-Api-Key", READ_KEY).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    mvc.perform(get("/api/charges").header("X-Api-Key", "fpb_test_wrong")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  /** Fails if a wrong key is ignored as if absent: a caller holding one valid credential could probe keys without ever being refused. */
  @Test
  void aWrongApiKeyIsRefusedEvenAlongsideAValidToken() throws Exception {
    mvc.perform(bearer(get("/api/charges"), valid(PREFIX + "payments:read")).header("X-Api-Key", "fpb_test_wrong"))
        .andExpect(status().isUnauthorized());
  }

  /** The dashboard learns how to sign in without credentials; the overview it then reads needs payments:read like any ledger. */
  @Test
  void theDashboardsSignInSettingsArePublicButItsDataIsNot() throws Exception {
    mvc.perform(get("/api/public/dashboard-config")).andExpect(status().isOk()).andExpect(jsonPath("$.auth").value("unavailable"));
    mvc.perform(get("/api/overview")).andExpect(status().isUnauthorized());
    mvc.perform(bearer(get("/api/overview"), valid(PREFIX + "payments:read"))).andExpect(status().isOk());
    mvc.perform(bearer(get("/api/overview"), valid(PREFIX + "payments:write"))).andExpect(status().isForbidden());
  }
}
