package dev.foliopay.config.security;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How the operator dashboard signs people in (D-38). With the API open (demo, fake gateway) it needs no login. With authentication on, an
 * operator signs in at the company's identity provider with OpenID Connect, authorization code flow with PKCE (no client secret in the
 * browser), and the dashboard calls the API with that access token, held in memory only. The browser may connect to the identity provider:
 * its origin is added to the content security policy, and nothing else is.
 */
@Component
public class DashboardAuth {
  /** What the dashboard is told: how to sign in, or that it cannot (the API needs credentials and no identity provider is configured). */
  public record Config(String auth, String authority, String clientId, String scope, boolean demo) {}

  private final Config config;
  private final String connectOrigins;

  public DashboardAuth(
      @Value("${foliopay.auth.jwt.jwk-set-uri:}") String jwkSetUri, @Value("${foliopay.auth.api-keys:}") String apiKeys,
      @Value("${foliopay.dashboard.oidc.authority:}") String authority, @Value("${foliopay.dashboard.oidc.client-id:}") String clientId,
      @Value("${foliopay.dashboard.oidc.scope:openid}") String scope, @Value("${foliopay.dashboard.oidc.connect-origins:}") String extraOrigins,
      @Value("${foliopay.demo-controls:false}") boolean demo) {
    boolean apiProtected = !jwkSetUri.isBlank() || !apiKeys.isBlank();
    boolean oidc = !authority.isBlank() && !clientId.isBlank();
    String mode = !apiProtected ? "none" : oidc ? "oidc" : "unavailable";
    this.config = new Config(mode, oidc ? authority : null, oidc ? clientId : null, oidc ? scope : null, demo);
    this.connectOrigins = oidc ? (origin(authority) + " " + extraOrigins).trim() : "";
  }

  public Config config() { return config; }

  /** Origins the browser may fetch from besides this one: the identity provider's (and its token endpoint's, if on another host). */
  public String connectOrigins() { return connectOrigins; }

  private static String origin(String url) {
    URI uri = URI.create(url);
    if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
      throw new IllegalStateException("foliopay.dashboard.oidc.authority must be an http(s) URL");
    }
    return uri.getScheme() + "://" + uri.getAuthority();
  }
}
