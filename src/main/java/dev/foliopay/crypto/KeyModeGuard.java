package dev.foliopay.crypto;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keys and database must outlive the process together, or not at all:
 *
 * <ul>
 *   <li>The throwaway in-memory master key ({@code local}) must never sit under a database that outlives the process: every stored card
 *       would become undecryptable at the next restart. Refused on anything but in-memory H2, unless explicitly allowed (the Compose demo).
 *   <li>Real keys ({@code kms}) on an in-memory database mean a production start that missed its database profile: payment state, dead
 *       letters included, would vanish at the next deploy, and each instance would keep its own. Always refused.
 * </ul>
 */
@Component
class KeyModeGuard {
  private final String keys;
  private final String datasourceUrl;
  private final boolean allowEphemeralKeysOnPersistentDatabase;

  KeyModeGuard(
      @Value("${foliopay.keys:}") String keys, @Value("${spring.datasource.url:}") String datasourceUrl,
          @Value("${foliopay.allow-ephemeral-keys:false}") boolean allow) {
    this.keys = keys;
    this.datasourceUrl = datasourceUrl;
    this.allowEphemeralKeysOnPersistentDatabase = allow;
  }

  @PostConstruct
  void check() {
    boolean inMemory = datasourceUrl.startsWith("jdbc:h2:mem:");
    if ("local".equals(keys) && !inMemory && !allowEphemeralKeysOnPersistentDatabase) {
      throw new IllegalStateException(
          "foliopay.keys=local is only for in-memory demos: its master key is lost on restart. Use foliopay.keys=kms, or set "
              + "foliopay.allow-ephemeral-keys=true for a throwaway database.");
    }
    if ("kms".equals(keys) && inMemory) {
      throw new IllegalStateException(
          "foliopay.keys=kms with an in-memory database: payment state would be lost at the next restart. Activate the postgres profile "
              + "(SPRING_PROFILES_ACTIVE=postgres) with DB_URL set.");
    }
  }
}
