package dev.foliopay.crypto;

/** Envelope encryption: a fresh data key per card, wrapped by a master key that never leaves the key service (AWS KMS in production). */
public interface KeyService {
  DataKey newDataKey(String context);

  byte[] unwrap(byte[] wrapped, String context);

  String keyId();
}
