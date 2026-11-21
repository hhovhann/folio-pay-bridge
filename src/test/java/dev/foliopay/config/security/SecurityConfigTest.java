package dev.foliopay.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/** The token rules on their own: which tokens are meant for this API, and what their scopes grant. */
@DisplayName("Access tokens: issuer, audience or scope prefix, scopes and the client")
class SecurityConfigTest {
  private static final String ISSUER = "https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_pool";
  private static final String PREFIX = "folio-pay-bridge/";

  /** A Cognito client-credentials access token: no aud, the client in client_id, token_use=access, scopes prefixed with the resource server. */
  private static Jwt cognito(UnaryOperator<Jwt.Builder> change) {
    Jwt.Builder token = Jwt.withTokenValue("t").header("alg", "RS256").issuer(ISSUER).subject("3abc").claim("client_id", "3abc")
        .claim("token_use", "access").claim("scope", PREFIX + "payments:read").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
    return change.apply(token).build();
  }

  /** Fails if Cognito's access tokens are refused for having no audience: the documented first authorization server would not work. */
  @Test
  @DisplayName("A Cognito access token without an audience is accepted when the scope prefix binds it to this API")
  void aCognitoAccessTokenWithoutAnAudienceIsAcceptedWhenTheScopePrefixBindsItToThisApi() {
    OAuth2TokenValidator<Jwt> rules = SecurityConfig.tokenValidator(ISSUER, "", PREFIX);

    assertThat(rules.validate(cognito(t -> t)).hasErrors()).isFalse();
    assertThat(rules.validate(cognito(t -> t.claim("token_use", "id"))).hasErrors()).as("an ID token is not an access token").isTrue();
    assertThat(rules.validate(cognito(t -> t.issuer("https://cognito-idp.eu-west-1.amazonaws.com/other"))).hasErrors()).as("another pool").isTrue();
    assertThat(rules.validate(cognito(t -> t.issuedAt(Instant.now().minusSeconds(900)).expiresAt(Instant.now().minusSeconds(600)))).hasErrors())
        .as("expired").isTrue();
  }

  @Test
  @DisplayName("Where the server sets an audience, it must name this API")
  void whereTheServerSetsAnAudienceItMustNameThisApi() {
    OAuth2TokenValidator<Jwt> rules = SecurityConfig.tokenValidator(ISSUER, "folio-pay-bridge", PREFIX);

    assertThat(rules.validate(cognito(t -> t.audience(List.of("folio-pay-bridge")))).hasErrors()).isFalse();
    assertThat(rules.validate(cognito(t -> t.audience(List.of("another-api")))).hasErrors()).isTrue();
    assertThat(rules.validate(cognito(t -> t)).hasErrors()).as("no audience at all").isTrue();
  }

  /** Fails if a configuration that binds tokens to nothing starts: any token from the issuer, for any API, would be let in. */
  @Test
  @DisplayName("A configuration with neither audience nor scope prefix is refused at start-up")
  void aConfigurationWithNeitherAudienceNorScopePrefixIsRefusedAtStartup() {
    assertThatThrownBy(() -> SecurityConfig.tokenValidator(ISSUER, "", "")).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("audience");
    assertThatThrownBy(() -> SecurityConfig.tokenValidator("", "folio-pay-bridge", "")).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("issuer");
  }

  /** Fails if, with a prefix configured, an unprefixed scope still counts: another API on the same server could name a scope "operations". */
  @Test
  @DisplayName("With a prefix, only this API's scopes count")
  void withAPrefixOnlyThisApisScopesCount() {
    Jwt token = cognito(t -> t.claim("scope", PREFIX + "payments:read operations other-api/payments:write " + PREFIX));

    assertThat(SecurityConfig.scopes(token, PREFIX)).extracting(GrantedAuthority::getAuthority).containsExactly("SCOPE_payments:read");
    assertThat(SecurityConfig.scopes(cognito(t -> t.claim("scope", "payments:read operations")), "")).extracting(GrantedAuthority::getAuthority)
        .containsExactly("SCOPE_payments:read", "SCOPE_operations");
  }

  /** Okta and Entra ID send scopes as a list in {@code scp}, not a space-separated {@code scope}: both are read. */
  @Test
  @DisplayName("Scopes sent as a list are read too")
  void scopesSentAsAListAreReadToo() {
    Jwt okta = cognito(t -> t.claims(c -> c.remove("scope")).claim("scp", List.of("payments:read", "operations")));

    assertThat(SecurityConfig.scopes(okta, "")).extracting(GrantedAuthority::getAuthority).containsExactly("SCOPE_payments:read", "SCOPE_operations");
  }

  @Test
  @DisplayName("The client is the client_id claim, or else the subject")
  void theClientIsTheClientIdClaimOrElseTheSubject() {
    assertThat(SecurityConfig.clientName(cognito(t -> t))).isEqualTo("3abc");
    assertThat(SecurityConfig.clientName(cognito(t -> t.claims(c -> c.remove("client_id")).subject("0oa-okta-client")))).isEqualTo("0oa-okta-client");
  }
}
