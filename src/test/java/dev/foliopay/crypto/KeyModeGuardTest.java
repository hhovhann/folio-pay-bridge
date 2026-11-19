package dev.foliopay.crypto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Key mode guard: which keys may run on which database")
class KeyModeGuardTest {
  @Test
  @DisplayName("Local keys are fine under an in-memory database")
  void localKeysAreFineUnderAnInMemoryDatabase() {
    assertThatCode(() -> new KeyModeGuard("local", "jdbc:h2:mem:folio;MODE=PostgreSQL", false).check()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Local keys under a persistent database are refused, because every stored card would die at restart")
  void localKeysUnderAPersistentDatabaseAreRefusedBecauseEveryStoredCardWouldDieAtRestart() {
    assertThatThrownBy(() -> new KeyModeGuard("local", "jdbc:postgresql://db:5432/folio", false).check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lost on restart");
  }

  @Test
  @DisplayName("A throwaway persistent database can be allowed explicitly")
  void aThrowawayPersistentDatabaseCanBeAllowedExplicitly() {
    assertThatCode(() -> new KeyModeGuard("local", "jdbc:postgresql://db:5432/folio", true).check()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("KMS keys on an in-memory database are refused, because payment state would vanish at the next deploy")
  void kmsKeysOnAnInMemoryDatabaseAreRefusedBecausePaymentStateWouldVanishAtTheNextDeploy() {
    assertThatThrownBy(() -> new KeyModeGuard("kms", "jdbc:h2:mem:folio;MODE=PostgreSQL", false).check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("postgres profile");
  }

  @Test
  @DisplayName("KMS keys on a persistent database start")
  void kmsKeysOnAPersistentDatabaseStart() {
    assertThatCode(() -> new KeyModeGuard("kms", "jdbc:postgresql://db:5432/folio", false).check()).doesNotThrowAnyException();
  }
}
