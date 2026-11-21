package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("Transport security: everything outside the process is reached over verified TLS")
class TransportSecurityGuardTest {
  private static final String RDS = "jdbc:postgresql://db.example.test:5432/folio";

  private static void check(MockEnvironment environment) {
    new TransportSecurityGuard(environment).check();
  }

  private static MockEnvironment httpGateway(String opera, String psp) {
    return new MockEnvironment().withProperty("foliopay.gateway", "http")
        .withProperty("foliopay.http.opera-base-url", opera).withProperty("foliopay.http.psp-base-url", psp);
  }

  @Test
  @DisplayName("OPERA or the processor over plain HTTP stops the start, naming the setting")
  void operaOrTheProcessorOverPlainHttpStopsTheStart() {
    assertThatThrownBy(() -> check(httpGateway("http://opera.example.test", "https://psp.example.test")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("foliopay.http.opera-base-url").hasMessageNotContaining("psp-base-url");
    assertThatThrownBy(() -> check(httpGateway("https://opera.example.test", "http://psp.example.test")))
        .hasMessageContaining("foliopay.http.psp-base-url");
  }

  @Test
  @DisplayName("Both adapters over https start")
  void bothAdaptersOverHttpsStart() {
    assertThatCode(() -> check(httpGateway("https://opera.example.test", "https://psp.example.test"))).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {RDS, RDS + "?sslmode=require", RDS + "?sslmode=verify-ca", RDS + "?ssl=true"})
  @DisplayName("PostgreSQL without verified TLS stops the start; encryption that accepts any server is not enough")
  void postgresWithoutVerifiedTlsStopsTheStart(String url) {
    assertThatThrownBy(() -> check(new MockEnvironment().withProperty("spring.datasource.url", url))).hasMessageContaining("spring.datasource.url");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {RDS + "?sslmode=verify-full", RDS + "?sslmode=verify-full&sslrootcert=/app/rds-ca.pem",
      RDS + "?sslrootcert=/app/rds-ca.pem&sslmode=verify-full",
      "jdbc:aws-wrapper:postgresql://db.example.test:5432/folio?sslmode=verify-full"})
  @DisplayName("PostgreSQL with sslmode=verify-full starts, wherever the option stands and through a wrapped driver")
  void postgresWithVerifyFullStarts(String url) {
    assertThatCode(() -> check(new MockEnvironment().withProperty("spring.datasource.url", url))).doesNotThrowAnyException();
  }

  /** Fails if a URL that names verify-full can still connect without checking the server (code review finding, phase 21). */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {RDS + "?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory",
      RDS + "?sslmode=verify-full&sslmode=disable", RDS + "?sslmode=verify-full&ssl=false",
      "jdbc:aws-wrapper:postgresql://db.example.test:5432/folio?sslmode=require"})
  @DisplayName("A URL that names verify-full but switches the check off, or overrides it, stops the start")
  void aUrlThatNamesVerifyFullButSwitchesTheCheckOffStopsTheStart(String url) {
    assertThatThrownBy(() -> check(new MockEnvironment().withProperty("spring.datasource.url", url))).hasMessageContaining("spring.datasource.url");
  }

  /** Split from the test above in phase 21: the pool's own data source properties can lower what the URL asks for. */
  @Test
  @DisplayName("A pool setting that lowers sslmode below verify-full stops the start")
  void aPoolSettingThatLowersSslmodeStopsTheStart() {
    MockEnvironment pooled = new MockEnvironment().withProperty("spring.datasource.url", RDS + "?sslmode=verify-full")
        .withProperty("spring.datasource.hikari.data-source-properties.sslmode", "require");
    assertThatThrownBy(() -> check(pooled)).hasMessageContaining("spring.datasource.url");
  }

  /** Fails if the signing keys can be fetched in the clear: whoever answers on that path could serve their own key and mint any token. */
  @Test
  @DisplayName("The authorization server's signing keys over plain HTTP stop the start; over https they start")
  void theSigningKeysOverPlainHttpStopTheStart() {
    assertThatThrownBy(() -> check(new MockEnvironment().withProperty("foliopay.auth.jwt.jwk-set-uri", "http://idp.example.test/jwks")))
        .hasMessageContaining("foliopay.auth.jwt.jwk-set-uri");
    assertThatCode(() -> check(new MockEnvironment().withProperty("foliopay.auth.jwt.jwk-set-uri", "https://idp.example.test/jwks")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("The in-memory database and the in-process fakes have no transport to protect")
  void theInMemoryDatabaseAndTheFakesHaveNoTransportToProtect() {
    MockEnvironment demo = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:h2:mem:folio")
        .withProperty("foliopay.gateway", "fake").withProperty("foliopay.http.opera-base-url", "http://unused.example.test");
    assertThatCode(() -> check(demo)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Plain transport is allowed only when named, for the local Compose stack")
  void plainTransportIsAllowedOnlyWhenNamed() {
    MockEnvironment compose = httpGateway("http://opera-sim:8090", "http://psp-sim:8091").withProperty("spring.datasource.url", RDS)
        .withProperty(TransportSecurityGuard.ALLOW, "true");
    assertThatCode(() -> check(compose)).doesNotThrowAnyException();
  }
}
