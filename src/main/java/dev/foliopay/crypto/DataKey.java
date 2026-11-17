package dev.foliopay.crypto;

/** A one-time data key: the plaintext is used in memory and dropped, the wrapped form is stored beside the ciphertext. */
public record DataKey(byte[] plaintext, byte[] wrapped) {
  @Override
  public String toString() { return "DataKey[redacted]"; }
}
