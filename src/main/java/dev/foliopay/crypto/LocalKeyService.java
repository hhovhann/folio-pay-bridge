package dev.foliopay.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Demo and test key service with an in-memory master key. Not for production: production uses {@link KmsKeyService}. */
@Component
@ConditionalOnProperty(name = "foliopay.keys", havingValue = "local")
public class LocalKeyService implements KeyService {
  private final byte[] master = Crypto.random(32);

  @Override
  public DataKey newDataKey(String context) {
    byte[] key = Crypto.random(32);
    return new DataKey(key, Crypto.seal(master, key, context));
  }

  @Override
  public byte[] unwrap(byte[] wrapped, String context) { return Crypto.open(master, wrapped, context); }

  @Override
  public String keyId() { return "local-demo-master-key"; }
}
