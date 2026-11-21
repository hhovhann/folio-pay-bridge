package dev.foliopay.config.security;

import dev.foliopay.api.ApiPaths;
import dev.foliopay.api.error.ErrorCode;
import dev.foliopay.api.error.Problems;
import dev.foliopay.logging.AuditLog;
import dev.foliopay.logging.AuditLog.Event;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Who may call the API, and for what (D-30). Two ways in, one scope model:
 *
 * <ul>
 *   <li><b>OAuth2 client credentials</b> (preferred): the integrator gets a short-lived signed JWT from the authorization server (Amazon
 *       Cognito, Okta, Keycloak, Entra ID) and sends {@code Authorization: Bearer}. This service is a resource server: it checks the
 *       signature against the server's published keys (JWKS), the issuer, the expiry, that the token is meant for this API, and reads the
 *       scopes.
 *   <li><b>API keys</b> ({@code X-Api-Key}) for integrators that cannot do OAuth2: hashed at rest, scoped the same way (see {@link ApiKeys}).
 * </ul>
 *
 * Scopes: {@code cards:write} (tokenise), {@code payments:write} (pay, pay a link), {@code links:write} (create a payment request: an app
 * that may ask for money cannot take it), {@code bookings:write} (booking events), {@code payments:read} (ledgers, links, notifications,
 * the overview), {@code operations} (replay, redeliver, reconcile, and anything else under /api). Stateless: no session and no cookie, so
 * CSRF does not apply. Fails closed: with a real gateway and neither mechanism configured the application does not start, unless
 * {@code foliopay.auth.disabled=true} is set by name (the local Compose demo only).
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
  static final String CARDS_WRITE = "SCOPE_cards:write";
  static final String PAYMENTS_WRITE = "SCOPE_payments:write";
  static final String LINKS_WRITE = "SCOPE_links:write";
  static final String BOOKINGS_WRITE = "SCOPE_bookings:write";
  static final String PAYMENTS_READ = "SCOPE_payments:read";
  static final String OPERATIONS = "SCOPE_operations";
  private static final String V1 = ApiPaths.V1;

  @Bean
  ApiKeys apiKeys(@Value("${foliopay.auth.api-keys:}") String config) {
    return new ApiKeys(config);
  }

  /**
   * What makes a token acceptable besides its signature: the configured issuer, not expired, meant for this API, and an access token.
   * "Meant for this API" is the audience claim where the authorization server sets one (Okta, Keycloak, Entra ID), or the scope prefix
   * where it does not: an Amazon Cognito client-credentials access token has no {@code aud}, and its scopes are prefixed with the resource
   * server id. At least one of the two is required. A Cognito ID token ({@code token_use=id}) is never accepted as an access token.
   */
  static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience, String scopePrefix) {
    if (issuer.isBlank()) {
      throw new IllegalStateException("foliopay.auth.jwt.issuer is required with foliopay.auth.jwt.jwk-set-uri");
    }
    if (audience.isBlank() && scopePrefix.isBlank()) {
      throw new IllegalStateException(
          "foliopay.auth.jwt.audience (or, for an authorization server that sets no audience such as Cognito, "
              + "foliopay.auth.jwt.scope-prefix) is required");
    }
    List<OAuth2TokenValidator<Jwt>> checks = new ArrayList<>();
    checks.add(JwtValidators.createDefaultWithIssuer(issuer));
    checks.add(new JwtClaimValidator<String>("token_use", use -> use == null || "access".equals(use)));
    if (!audience.isBlank()) {
      checks.add(new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience)));
    }
    return new DelegatingOAuth2TokenValidator<>(checks);
  }

  static JwtDecoder jwtDecoder(String jwkSetUri, String issuer, String audience, String scopePrefix) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    decoder.setJwtValidator(tokenValidator(issuer, audience, scopePrefix));
    return decoder;
  }

  @Bean
  @SuppressWarnings({"checkstyle:MethodLength", "checkstyle:ParameterNumber"})
  SecurityFilterChain api(
      HttpSecurity http, ApiKeys apiKeys,
      @Value("${foliopay.auth.jwt.jwk-set-uri:}") String jwkSetUri, @Value("${foliopay.auth.jwt.issuer:}") String issuer,
      @Value("${foliopay.auth.jwt.audience:}") String audience, @Value("${foliopay.auth.jwt.scope-prefix:}") String scopePrefix,
      @Value("${foliopay.auth.disabled:false}") boolean disabled, @Value("${foliopay.gateway:}") String gateway) throws Exception {
    boolean oauth2 = !jwkSetUri.isBlank();
    boolean enforced = oauth2 || !apiKeys.isEmpty();
    boolean realMoney = !gateway.isBlank() && !"fake".equals(gateway);
    if (!enforced && realMoney && !disabled) {
      throw new IllegalStateException(
          "No authentication configured: set foliopay.auth.jwt.* (OAuth2) or foliopay.auth.api-keys. foliopay.auth.disabled=true is for a "
              + "local demo only.");
    }
    AuthenticationEntryPoint refuse = SecurityConfig::unauthorized;
    http.csrf(AbstractHttpConfigurer::disable) // no session, no cookie: a forged cross-site request carries no credentials
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(AbstractHttpConfigurer::disable) // SecurityHeadersFilter owns them, tested, for every response
        .requestCache(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(e -> e.authenticationEntryPoint(refuse).accessDeniedHandler(forbidden()))
        .addFilterBefore(new ClientLogContextFilter(), AuthorizationFilter.class);
    if (!enforced) {
      return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build(); // demo and tests on the fake gateway only
    }
    http.addFilterBefore(new ApiKeyAuthenticationFilter(apiKeys, refuse), BearerTokenAuthenticationFilter.class);
    if (oauth2) {
      JwtDecoder decoder = jwtDecoder(jwkSetUri, issuer, audience, scopePrefix);
      http.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)
          .jwtAuthenticationConverter(jwt -> new JwtAuthenticationToken(jwt, scopes(jwt, scopePrefix), clientName(jwt))))
          .authenticationEntryPoint(refuse));
    }
    return http.authorizeHttpRequests(a -> a
            .requestMatchers(HttpMethod.GET, V1 + "/public/**").permitAll() // how to sign in: no secret in it
            .requestMatchers(HttpMethod.POST, V1 + "/cards").hasAuthority(CARDS_WRITE)
            .requestMatchers(HttpMethod.POST, V1 + "/payments", V1 + "/links/*/pay").hasAuthority(PAYMENTS_WRITE)
            .requestMatchers(HttpMethod.POST, V1 + "/links").hasAuthority(LINKS_WRITE)
            .requestMatchers(HttpMethod.POST, V1 + "/booking-events").hasAuthority(BOOKINGS_WRITE)
            .requestMatchers(HttpMethod.GET, V1 + "/charges", V1 + "/postings", V1 + "/links", V1 + "/notifications", V1 + "/overview")
            .hasAuthority(PAYMENTS_READ)
            // replay, redeliver, reconcile, and anything added later: least access by default
            .requestMatchers(ApiPaths.ALL_VERSIONS).hasAuthority(OPERATIONS)
            .anyRequest().permitAll()) // health probes, the dashboard and console pages, the API description (off unless a profile turns it on)
        .build();
  }

  /**
   * The client a token was issued to: {@code client_id} (Cognito, Keycloak, OAuth2 token exchange), else the subject (Okta and Entra ID
   * put the client there for client credentials). It names the client in every log line and owns its idempotency keys (D-40).
   */
  static String clientName(Jwt jwt) {
    String clientId = jwt.getClaimAsString("client_id");
    return clientId != null && !clientId.isBlank() ? clientId : jwt.getSubject();
  }

  /**
   * {@code scope} (space separated) or {@code scp} (a list). With a prefix configured (Cognito's resource-server id) only scopes that
   * carry it count, with the prefix removed: a scope another API on the same authorization server happens to call {@code operations} grants
   * nothing here.
   */
  static Collection<GrantedAuthority> scopes(Jwt jwt, String prefix) {
    List<String> raw = new ArrayList<>();
    Object scope = jwt.getClaims().get("scope");
    if (scope instanceof String s) {
      raw.addAll(List.of(s.trim().split("\\s+")));
    }
    Object scp = jwt.getClaims().get("scp");
    if (scp instanceof Collection<?> list) {
      list.forEach(v -> raw.add(String.valueOf(v)));
    }
    return raw.stream()
        .filter(s -> !s.isBlank())
        .filter(s -> prefix.isEmpty() || s.startsWith(prefix))
        .map(s -> s.substring(prefix.length()))
        .filter(s -> !s.isEmpty())
        .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s))
        .toList();
  }

  private static void unauthorized(HttpServletRequest request, HttpServletResponse response, Exception e) throws IOException {
    AuditLog.event(Event.AUTHENTICATION_FAILED).client("unauthenticated").resource(request.getRequestURI())
        .detail("method", request.getMethod()).detail("origin", request.getRemoteAddr()).detail("reason", e.getClass().getSimpleName()).failure();
    response.setHeader("WWW-Authenticate", "Bearer");
    Problems.write(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Missing, unknown or expired credentials");
  }

  private static AccessDeniedHandler forbidden() {
    return (request, response, e) -> {
      Authentication caller = SecurityContextHolder.getContext().getAuthentication();
      AuditLog.event(Event.ACCESS_DENIED).client(caller == null ? null : caller.getName()).resource(request.getRequestURI())
          .detail("method", request.getMethod()).detail("origin", request.getRemoteAddr()).failure();
      Problems.write(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN, "These credentials do not allow this operation");
    };
  }
}
