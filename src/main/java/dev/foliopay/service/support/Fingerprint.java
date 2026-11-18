package dev.foliopay.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A short, one-way label for a secret-ish id (a card token), so it can sit in keys and tables without being usable as the token. */
public final class Fingerprint {
  private static final int HEX_CHARS = 16;

  private Fingerprint() {}

  public static String of(String value) {
    return HexFormat.of().formatHex(sha256(value)).substring(0, HEX_CHARS);
  }

  public static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
