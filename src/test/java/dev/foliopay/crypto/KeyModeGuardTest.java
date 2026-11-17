package dev.foliopay.crypto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeyModeGuardTest {
  @Test
  void localKeysAreFineUnderAnInMemoryDatabase() {
    assertThatCode(() -> new KeyModeGuard("jdbc:h2:mem:folio;MODE=PostgreSQL", false).check()).doesNotThrowAnyException();
  }

  @Test
  void localKeysUnderAPersistentDatabaseAreRefusedBecauseEveryStoredCardWouldDieAtRestart() {
    assertThatThrownBy(() -> new KeyModeGuard("jdbc:postgresql://db:5432/folio", false).check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lost on restart");
  }

  @Test
  void aThrowawayPersistentDatabaseCanBeAllowedExplicitly() {
    assertThatCode(() -> new KeyModeGuard("jdbc:postgresql://db:5432/folio", true).check()).doesNotThrowAnyException();
  }
}
