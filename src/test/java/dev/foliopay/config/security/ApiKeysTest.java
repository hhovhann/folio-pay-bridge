package dev.foliopay.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.service.support.Fingerprint;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ApiKeysTest {
  private static String hash(String key) { return HexFormat.of().formatHex(Fingerprint.sha256(key)); }

  @Test
  void aKeyIsFoundByItsHashWithItsScopesAndAnUnknownKeyIsNot() {
    ApiKeys keys = new ApiKeys("pms:" + hash("fpb_test_a") + ":bookings:write payments:read, app:" + hash("fpb_test_b") + ":payments:write");

    assertThat(keys.find("fpb_test_a")).hasValueSatisfying(c -> {
      assertThat(c.name()).isEqualTo("pms");
      assertThat(c.scopes()).containsExactlyInAnyOrder("bookings:write", "payments:read");
    });
    assertThat(keys.find("fpb_test_b").orElseThrow().scopes()).containsExactly("payments:write");
    assertThat(keys.find("fpb_test_c")).isEmpty();
    assertThat(keys.find(hash("fpb_test_a"))).as("the stored hash is not itself a key").isEmpty();
  }

  /** Fails if a mistyped entry is skipped: the operator would believe a client can call in, or worse, that a removed key is gone. */
  @Test
  void anEntryThatIsNotNameHashScopesStopsStartUp() {
    for (String bad : new String[] {"pms:" + hash("k"), "pms:not-a-hash:payments:read", ":" + hash("k") + ":payments:read", "pms:" + hash("k") + ": "}) {
      assertThatThrownBy(() -> new ApiKeys(bad)).as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("name:sha256hex:scope");
    }
    assertThat(new ApiKeys(" , ").isEmpty()).isTrue();
  }
}
