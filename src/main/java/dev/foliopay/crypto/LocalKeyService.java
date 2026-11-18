package dev.foliopay.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Demo and test key service with an in-memory master key. Not for production: production uses {@link KmsKeyService}. */
@Component
@ConditionalOnProperty(name = "foliopay.keys", havingValue = "local")
public class LocalKeyService implements KeyService {
  static final String KEY_ID = "local-demo-master-key";
  private final byte[] master = Crypto.random(32);

  @Override
  public DataKey newDataKey(String context) {
    byte[] key = Crypto.random(32);
    return new DataKey(key, Crypto.seal(master, key, context), KEY_ID);
  }

  @Override
  public byte[] unwrap(byte[] wrapped, String context, String keyId) { return Crypto.open(master, wrapped, context); }
}
