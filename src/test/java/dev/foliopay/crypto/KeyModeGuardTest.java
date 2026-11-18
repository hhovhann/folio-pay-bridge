package dev.foliopay.crypto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeyModeGuardTest {
  @Test
  void localKeysAreFineUnderAnInMemoryDatabase() {
    assertThatCode(() -> new KeyModeGuard("local", "jdbc:h2:mem:folio;MODE=PostgreSQL", false).check()).doesNotThrowAnyException();
  }

  @Test
  void localKeysUnderAPersistentDatabaseAreRefusedBecauseEveryStoredCardWouldDieAtRestart() {
    assertThatThrownBy(() -> new KeyModeGuard("local", "jdbc:postgresql://db:5432/folio", false).check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lost on restart");
  }

  @Test
  void aThrowawayPersistentDatabaseCanBeAllowedExplicitly() {
    assertThatCode(() -> new KeyModeGuard("local", "jdbc:postgresql://db:5432/folio", true).check()).doesNotThrowAnyException();
  }

  @Test
  void kmsKeysOnAnInMemoryDatabaseAreRefusedBecausePaymentStateWouldVanishAtTheNextDeploy() {
    assertThatThrownBy(() -> new KeyModeGuard("kms", "jdbc:h2:mem:folio;MODE=PostgreSQL", false).check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("postgres profile");
  }

  @Test
  void kmsKeysOnAPersistentDatabaseStart() {
    assertThatCode(() -> new KeyModeGuard("kms", "jdbc:postgresql://db:5432/folio", false).check()).doesNotThrowAnyException();
  }
}
