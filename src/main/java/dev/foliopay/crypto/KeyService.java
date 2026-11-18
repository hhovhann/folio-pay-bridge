package dev.foliopay.crypto;

/** Envelope encryption: a fresh data key per card, wrapped by a master key that never leaves the key service (AWS KMS in production). */
public interface KeyService {
  DataKey newDataKey(String context);

  /** {@code keyId} is the one stored with the token ({@link DataKey#keyId()}), not whatever the configured alias points at today. */
  byte[] unwrap(byte[] wrapped, String context, String keyId);
}
