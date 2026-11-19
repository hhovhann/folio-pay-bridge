package dev.foliopay.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/** Checks how the adapter talks to KMS against a stubbed client. It is NOT run against real AWS: see docs/aws-deployment.md section 5. */
@DisplayName("KMS key service: how the adapter talks to KMS, against a stubbed client")
class KmsKeyServiceTest {
  private static final byte[] PLAIN = new byte[32];
  private static final byte[] WRAPPED = {1, 2, 3};
  private static final String KEY_ARN = "arn:aws:kms:me-central-1:111122223333:key/1111aaaa-2222-bbbb-3333-cccc4444dddd";
  private final KmsClient kms = mock(KmsClient.class);
  private final KmsKeyService service = new KmsKeyService(kms, "alias/cards");

  @Test
  @DisplayName("Asks KMS for an AES-256 key bound to the token")
  void asksKmsForAnAes256KeyBoundToTheToken() {
    when(kms.generateDataKey(any(GenerateDataKeyRequest.class)))
        .thenReturn(GenerateDataKeyResponse.builder().plaintext(SdkBytes.fromByteArray(PLAIN)).ciphertextBlob(SdkBytes.fromByteArray(WRAPPED)).keyId(KEY_ARN).build());

    DataKey key = service.newDataKey("tok_1");

    ArgumentCaptor<GenerateDataKeyRequest> request = ArgumentCaptor.forClass(GenerateDataKeyRequest.class);
    verify(kms).generateDataKey(request.capture());
    assertThat(request.getValue().keyId()).isEqualTo("alias/cards");
    assertThat(request.getValue().keySpec()).isEqualTo(DataKeySpec.AES_256);
    assertThat(request.getValue().encryptionContext()).isEqualTo(Map.of("token", "tok_1"));
    assertThat(key.wrapped()).isEqualTo(WRAPPED);
    assertThat(key.keyId()).as("the ARN of the key that wrapped it, never the alias, is what the token stores").isEqualTo(KEY_ARN);
  }

  @Test
  @DisplayName("Unwraps with the same encryption context")
  void unwrapsWithTheSameEncryptionContext() {
    when(kms.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.builder().plaintext(SdkBytes.fromByteArray(PLAIN)).build());

    assertThat(service.unwrap(WRAPPED, "tok_1", KEY_ARN)).isEqualTo(PLAIN);

    ArgumentCaptor<DecryptRequest> request = ArgumentCaptor.forClass(DecryptRequest.class);
    verify(kms).decrypt(request.capture());
    assertThat(request.getValue().encryptionContext()).isEqualTo(Map.of("token", "tok_1"));
  }

  /** Fails if decryption names the configured alias: after the alias moves to a new key, every older card would be unreadable. */
  @Test
  @DisplayName("A card wrapped before the key alias moved is decrypted with the key that wrapped it")
  void aCardWrappedBeforeTheAliasMovedIsDecryptedWithTheKeyThatWrappedIt() {
    when(kms.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.builder().plaintext(SdkBytes.fromByteArray(PLAIN)).build());

    service.unwrap(WRAPPED, "tok_1", KEY_ARN);

    ArgumentCaptor<DecryptRequest> request = ArgumentCaptor.forClass(DecryptRequest.class);
    verify(kms).decrypt(request.capture());
    assertThat(request.getValue().keyId()).isEqualTo(KEY_ARN).isNotEqualTo("alias/cards");
  }
}
