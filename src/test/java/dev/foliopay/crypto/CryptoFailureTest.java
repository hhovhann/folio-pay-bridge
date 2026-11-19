package dev.foliopay.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foliopay.service.support.Fingerprint;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kms.KmsClient;

@DisplayName("Envelope encryption: keys of the wrong size are refused")
class CryptoFailureTest {
  private static final byte[] PLAIN = "4111111111111111".getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("A key of the wrong size is refused when sealing")
  void aKeyOfTheWrongSizeIsRefusedWhenSealing() {
    assertThatThrownBy(() -> Crypto.seal(new byte[5], PLAIN, "tok_1")).isInstanceOf(IllegalStateException.class).hasMessage("Encryption failed");
  }

  @Test
  @DisplayName("A key of the wrong size is refused when opening")
  void aKeyOfTheWrongSizeIsRefusedWhenOpening() {
    byte[] sealed = Crypto.seal(Crypto.random(32), PLAIN, "tok_1");
    assertThatThrownBy(() -> Crypto.open(new byte[5], sealed, "tok_1")).isInstanceOf(IllegalStateException.class).hasMessage("Decryption failed");
  }

  @Test
  @DisplayName("The local key service names itself as the demo key and wraps and unwraps with the context")
  void theLocalKeyServiceNamesItselfAsTheDemoKeyAndWrapsAndUnwrapsWithTheContext() {
    LocalKeyService service = new LocalKeyService();
    DataKey key = service.newDataKey("tok_1");
    assertThat(key.keyId()).isEqualTo("local-demo-master-key");
    assertThat(service.unwrap(key.wrapped(), "tok_1", key.keyId())).isEqualTo(key.plaintext());
    assertThatThrownBy(() -> service.unwrap(key.wrapped(), "tok_2", key.keyId())).as("another token's context cannot unwrap it").isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("The KMS client is built from the default AWS chain, with no keys in configuration")
  void theKmsClientIsBuiltFromTheDefaultAwsChainWithNoKeysInConfiguration() {
    String[] names = {"aws.region", "aws.accessKeyId", "aws.secretAccessKey"};
    String[] before = new String[names.length];
    for (int i = 0; i < names.length; i++) before[i] = System.getProperty(names[i]);
    System.setProperty("aws.region", "eu-west-1");
    System.setProperty("aws.accessKeyId", "test");
    System.setProperty("aws.secretAccessKey", "test");
    try (KmsClient client = new KmsConfig().kmsClient()) {
      assertThat(client).isNotNull();
    } finally {
      for (int i = 0; i < names.length; i++) {
        if (before[i] == null) System.clearProperty(names[i]); else System.setProperty(names[i], before[i]);
      }
    }
  }

  @Test
  @DisplayName("A fingerprint is stable, short and not the value")
  void aFingerprintIsStableShortAndNotTheValue() {
    assertThat(Fingerprint.of("tok_abc")).isEqualTo(Fingerprint.of("tok_abc")).hasSize(16).doesNotContain("tok_abc");
    assertThat(Fingerprint.of("tok_abc")).isNotEqualTo(Fingerprint.of("tok_abd"));
  }
}
