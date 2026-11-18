package dev.foliopay.crypto;

/**
 * A one-time data key: the plaintext is used in memory and dropped, the wrapped form is stored beside the ciphertext, together with the id
 * of the master key that wrapped it (for KMS the key ARN, never an alias), so the card stays readable after the alias is moved to a new key.
 */
public record DataKey(byte[] plaintext, byte[] wrapped, String keyId) {
  @Override
  public String toString() { return "DataKey[redacted]"; }
}
