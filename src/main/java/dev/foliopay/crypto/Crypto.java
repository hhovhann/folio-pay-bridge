package dev.foliopay.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM with a fresh 96-bit IV and the token id as associated data, so a ciphertext cannot be moved to another token. */
public final class Crypto {
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RNG = new SecureRandom();

  private Crypto() {}

  public static byte[] seal(byte[] key, byte[] plain, String aad) {
    try {
      byte[] iv = random(IV_BYTES);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] cipherText = cipher.doFinal(plain);
      return ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Encryption failed", e);
    }
  }

  public static byte[] open(byte[] key, byte[] sealed, String aad) {
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Decryption failed", e);
    }
  }

  public static byte[] random(int bytes) {
    byte[] b = new byte[bytes];
    RNG.nextBytes(b);
    return b;
  }
}
