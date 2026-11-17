package dev.foliopay.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CryptoTest {
  private final byte[] key = Crypto.random(32);
  private final byte[] plain = "4111111111111111".getBytes(StandardCharsets.UTF_8);

  @Test
  void opensWhatItSealed() {
    assertThat(Crypto.open(key, Crypto.seal(key, plain, "tok_1"), "tok_1")).isEqualTo(plain);
  }

  @Test
  void ciphertextHoldsNoPlaintextAndTwoSealsDiffer() {
    byte[] first = Crypto.seal(key, plain, "tok_1");
    assertThat(new String(first, StandardCharsets.ISO_8859_1)).doesNotContain("4111111111111111");
    assertThat(first).isNotEqualTo(Crypto.seal(key, plain, "tok_1")); // fresh IV every time
  }

  @Test
  void aCiphertextCannotBeMovedToAnotherToken() {
    byte[] sealed = Crypto.seal(key, plain, "tok_1");
    assertThatThrownBy(() -> Crypto.open(key, sealed, "tok_2")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aTamperedCiphertextIsRejected() {
    byte[] sealed = Crypto.seal(key, plain, "tok_1");
    sealed[sealed.length - 1] ^= 1;
    assertThatThrownBy(() -> Crypto.open(key, sealed, "tok_1")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aWrongKeyIsRejected() {
    byte[] sealed = Crypto.seal(key, plain, "tok_1");
    assertThatThrownBy(() -> Crypto.open(Crypto.random(32), sealed, "tok_1")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void dataKeyToStringRevealsNothing() {
    assertThat(new DataKey(new byte[] {1, 2, 3}, new byte[] {4}).toString()).isEqualTo("DataKey[redacted]");
    assertThat(Arrays.toString(key)).isNotEmpty();
  }
}
