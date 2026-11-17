package dev.foliopay.crypto;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The throwaway in-memory master key must never sit under a database that outlives the process: every stored card would become undecryptable
 * at the next restart. Refuses to start with local keys on anything but in-memory H2, unless that is explicitly allowed (the Compose demo).
 */
@Component
@ConditionalOnProperty(name = "foliopay.keys", havingValue = "local")
class KeyModeGuard {
  private final String datasourceUrl;
  private final boolean allowEphemeralKeysOnPersistentDatabase;

  KeyModeGuard(@Value("${spring.datasource.url:}") String datasourceUrl, @Value("${foliopay.allow-ephemeral-keys:false}") boolean allow) {
    this.datasourceUrl = datasourceUrl;
    this.allowEphemeralKeysOnPersistentDatabase = allow;
  }

  @PostConstruct
  void check() {
    boolean inMemory = datasourceUrl.startsWith("jdbc:h2:mem:");
    if (!inMemory && !allowEphemeralKeysOnPersistentDatabase) {
      throw new IllegalStateException(
          "foliopay.keys=local is only for in-memory demos: its master key is lost on restart. Use foliopay.keys=kms, or set foliopay.allow-ephemeral-keys=true for a throwaway database.");
    }
  }
}
