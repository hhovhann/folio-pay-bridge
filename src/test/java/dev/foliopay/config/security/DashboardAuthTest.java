package dev.foliopay.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Dashboard sign-in settings: open, with an identity provider, or unavailable")
class DashboardAuthTest {
  private static DashboardAuth auth(String jwks, String keys, String authority, String clientId, String extra) {
    return new DashboardAuth(jwks, keys, authority, clientId, "openid https://pay.example.test/payments:read", extra, false);
  }

  @Test
  @DisplayName("An open API needs no sign-in and adds no origin to the content security policy")
  void anOpenApiNeedsNoSignInAndAddsNoOriginToThePolicy() {
    DashboardAuth open = auth("", "", "", "", "");
    assertThat(open.config().auth()).isEqualTo("none");
    assertThat(open.connectOrigins()).isEmpty();
  }

  @Test
  @DisplayName("A protected API with an identity provider signs in with OIDC, and only that provider may be contacted")
  void aProtectedApiWithAnIdentityProviderSignsInWithOidcAndOnlyThatProviderMayBeContacted() {
    DashboardAuth oidc = auth("https://idp.example.test/jwks", "", "https://idp.example.test/tenant-1", "dashboard", "https://login.example.test");
    assertThat(oidc.config().auth()).isEqualTo("oidc");
    assertThat(oidc.config().clientId()).isEqualTo("dashboard");
    assertThat(oidc.connectOrigins()).isEqualTo("https://idp.example.test https://login.example.test");
  }

  /** Fails if a protected API lets the dashboard pretend it can sign in: it must say plainly that sign-in is not configured. */
  @Test
  @DisplayName("A protected API without an identity provider says sign-in is unavailable")
  void aProtectedApiWithoutAnIdentityProviderSaysSignInIsUnavailable() {
    DashboardAuth keysOnly = auth("", "pms:" + "0".repeat(64) + ":payments:read", "", "", "");
    assertThat(keysOnly.config().auth()).isEqualTo("unavailable");
    assertThat(keysOnly.config().authority()).isNull();
  }

  @Test
  @DisplayName("An authority that is not an HTTP URL is refused at start")
  void anAuthorityThatIsNotAnHttpUrlIsRefusedAtStart() {
    assertThatThrownBy(() -> auth("https://idp.example.test/jwks", "", "javascript:alert(1)", "dashboard", ""))
        .isInstanceOf(IllegalStateException.class);
  }
}
