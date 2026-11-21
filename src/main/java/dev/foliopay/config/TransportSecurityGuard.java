package dev.foliopay.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Everything outside the process is reached over TLS that checks who answers (PCI DSS v4.0.1 4.2.1, D-45): OPERA and the processor over
 * {@code https}, the authorization server's signing keys over {@code https} (whoever serves them decides which tokens are genuine), and
 * PostgreSQL with {@code sslmode=verify-full} ({@code require} encrypts but accepts any server). The application refuses to start
 * otherwise, unless {@code foliopay.allow-plaintext-transport=true} is set by name: the local Compose stack, where the simulators and the
 * database are containers on one private network. In-memory H2 and the in-process fakes have no transport to protect.
 */
@Component
class TransportSecurityGuard {
  static final String ALLOW = "foliopay.allow-plaintext-transport";
  private static final String HIKARI_SSLMODE = "spring.datasource.hikari.data-source-properties.sslmode";
  private final Environment environment;

  TransportSecurityGuard(Environment environment) {
    this.environment = environment;
  }

  @PostConstruct
  void check() {
    if (environment.getProperty(ALLOW, Boolean.class, false)) {
      return;
    }
    List<String> plaintext = new ArrayList<>();
    if ("http".equals(environment.getProperty("foliopay.gateway"))) {
      requireHttps("foliopay.http.opera-base-url", plaintext);
      requireHttps("foliopay.http.psp-base-url", plaintext);
    }
    requireHttps("foliopay.auth.jwt.jwk-set-uri", plaintext);
    requireVerifiedDatabase(plaintext);
    if (!plaintext.isEmpty()) {
      throw new IllegalStateException("Connections outside the process must use verified TLS: " + String.join(", ", plaintext)
          + ". Set " + ALLOW + "=true for a local demo only.");
    }
  }

  private void requireHttps(String name, List<String> plaintext) {
    String url = environment.getProperty(name, "");
    if (!url.isBlank() && !url.startsWith("https://")) {
      plaintext.add(name + " (use https)");
    }
  }

  /**
   * Every {@code sslmode} the driver could read must be {@code verify-full}, in the URL (where a later one wins) or in the pool's data
   * source properties, and no factory may switch the certificate check off. A driver wrapped in another URL form is checked the same way.
   */
  private void requireVerifiedDatabase(List<String> plaintext) {
    String url = environment.getProperty("spring.datasource.url", "");
    if (!url.startsWith("jdbc:") || !url.toLowerCase(Locale.ROOT).contains("postgresql:")) {
      return;
    }
    List<String> modes = new ArrayList<>();
    boolean unverifiedFactory = false;
    int query = url.indexOf('?');
    for (String option : query < 0 ? new String[0] : url.substring(query + 1).split("&")) {
      String[] pair = option.split("=", 2);
      String key = pair[0].toLowerCase(Locale.ROOT);
      String value = pair.length > 1 ? pair[1] : "";
      if (key.equals("sslmode")) {
        modes.add(value);
      }
      unverifiedFactory |= key.equals("sslfactory") && value.contains("NonValidating") || key.equals("ssl") && value.equals("false");
    }
    String pooled = environment.getProperty(HIKARI_SSLMODE);
    if (pooled != null) {
      modes.add(pooled);
    }
    if (modes.isEmpty() || !modes.stream().allMatch("verify-full"::equals) || unverifiedFactory) {
      plaintext.add("spring.datasource.url (every sslmode verify-full, the server's CA as sslrootcert, no non-validating sslfactory)");
    }
  }
}
