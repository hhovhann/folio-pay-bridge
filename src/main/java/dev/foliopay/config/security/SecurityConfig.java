package dev.foliopay.config.security;

import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import dev.foliopay.api.error.ErrorCode;
import dev.foliopay.config.JsonErrors;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

/**
 * Who may call the API, and for what (D-30). Two ways in, one scope model:
 *
 * <ul>
 *   <li><b>OAuth2 client credentials</b> (preferred): the integrator gets a short-lived signed JWT from the authorization server (Amazon
 *       Cognito, Okta, Keycloak) and sends {@code Authorization: Bearer}. This service is a resource server: it checks the signature
 *       against the server's published keys (JWKS), the issuer, the audience and the expiry, and reads the scopes.
 *   <li><b>API keys</b> ({@code X-Api-Key}) for integrators that cannot do OAuth2: hashed at rest, scoped the same way (see {@link ApiKeys}).
 * </ul>
 *
 * Scopes: {@code cards:write} (tokenise), {@code payments:write} (pay, pay a link, create a payment request), {@code bookings:write}
 * (booking events), {@code payments:read} (ledgers, links, notifications, the overview), {@code operations} (replay, redeliver, reconcile, and anything
 * else under /api). Stateless: no session and no cookie, so CSRF does not apply. Fails closed: with a real gateway and neither mechanism
 * configured the application does not start, unless {@code foliopay.auth.disabled=true} is set by name (the local Compose demo only).
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
  static final String CARDS_WRITE = "SCOPE_cards:write";
  static final String PAYMENTS_WRITE = "SCOPE_payments:write";
  static final String BOOKINGS_WRITE = "SCOPE_bookings:write";
  static final String PAYMENTS_READ = "SCOPE_payments:read";
  static final String OPERATIONS = "SCOPE_operations";

  @Bean
  ApiKeys apiKeys(@Value("${foliopay.auth.api-keys:}") String config) {
    return new ApiKeys(config);
  }

  /** Issuer and audience are required with a JWKS address, so a token minted for another API, or by another server, is refused. */
  static JwtDecoder jwtDecoder(String jwkSetUri, String issuer, String audience) {
    if (issuer.isBlank() || audience.isBlank()) {
      throw new IllegalStateException("foliopay.auth.jwt.issuer and foliopay.auth.jwt.audience are required with foliopay.auth.jwt.jwk-set-uri");
    }
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
    return decoder;
  }

  @Bean
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
          "No authentication configured: set foliopay.auth.jwt.* (OAuth2) or foliopay.auth.api-keys. foliopay.auth.disabled=true is for a local demo only.");
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
      JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
      converter.setJwtGrantedAuthoritiesConverter(jwt -> scopes(jwt, scopePrefix));
      JwtDecoder decoder = jwtDecoder(jwkSetUri, issuer, audience);
      http.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter)).authenticationEntryPoint(refuse));
    }
    return http.authorizeHttpRequests(a -> a
            .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll() // how to sign in: no secret in it
            .requestMatchers(HttpMethod.POST, "/api/cards").hasAuthority(CARDS_WRITE)
            .requestMatchers(HttpMethod.POST, "/api/payments", "/api/links", "/api/links/*/pay").hasAuthority(PAYMENTS_WRITE)
            .requestMatchers(HttpMethod.POST, "/api/booking-events").hasAuthority(BOOKINGS_WRITE)
            .requestMatchers(HttpMethod.GET, "/api/charges", "/api/postings", "/api/links", "/api/notifications", "/api/overview").hasAuthority(PAYMENTS_READ)
            .requestMatchers("/api/**").hasAuthority(OPERATIONS) // replay, redeliver, reconcile, and anything added later: least access by default
            .anyRequest().permitAll()) // health probes, the static console page, the API description (off unless a profile turns it on)
        .build();
  }

  /** {@code scope} (space separated) or {@code scp} (a list); an authorization server's prefix (Cognito's resource-server id) is removed. */
  static Collection<GrantedAuthority> scopes(Jwt jwt, String prefix) {
    List<String> raw = new ArrayList<>();
    Object scope = jwt.getClaims().get("scope");
    if (scope instanceof String s) raw.addAll(List.of(s.trim().split("\\s+")));
    Object scp = jwt.getClaims().get("scp");
    if (scp instanceof Collection<?> list) list.forEach(v -> raw.add(String.valueOf(v)));
    return raw.stream().filter(s -> !s.isBlank()).map(s -> !prefix.isEmpty() && s.startsWith(prefix) ? s.substring(prefix.length()) : s)
        .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s)).toList();
  }

  private static void unauthorized(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response, Exception e) throws java.io.IOException {
    response.setHeader("WWW-Authenticate", "Bearer");
    JsonErrors.write(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Missing, unknown or expired credentials");
  }

  private static AccessDeniedHandler forbidden() {
    return (request, response, e) -> JsonErrors.write(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN, "These credentials do not allow this operation");
  }
}
