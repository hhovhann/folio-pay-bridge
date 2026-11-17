package dev.foliopay.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/** Checks how the adapter talks to KMS against a stubbed client. It is NOT run against real AWS: see docs/aws.md. */
class KmsKeyServiceTest {
  private static final byte[] PLAIN = new byte[32];
  private static final byte[] WRAPPED = {1, 2, 3};
  private final KmsClient kms = mock(KmsClient.class);
  private final KmsKeyService service = new KmsKeyService(kms, "alias/cards");

  @Test
  void asksKmsForAnAes256KeyBoundToTheToken() {
    when(kms.generateDataKey(any(GenerateDataKeyRequest.class)))
        .thenReturn(GenerateDataKeyResponse.builder().plaintext(SdkBytes.fromByteArray(PLAIN)).ciphertextBlob(SdkBytes.fromByteArray(WRAPPED)).build());

    DataKey key = service.newDataKey("tok_1");

    ArgumentCaptor<GenerateDataKeyRequest> request = ArgumentCaptor.forClass(GenerateDataKeyRequest.class);
    verify(kms).generateDataKey(request.capture());
    assertThat(request.getValue().keyId()).isEqualTo("alias/cards");
    assertThat(request.getValue().keySpec()).isEqualTo(DataKeySpec.AES_256);
    assertThat(request.getValue().encryptionContext()).isEqualTo(Map.of("token", "tok_1"));
    assertThat(key.wrapped()).isEqualTo(WRAPPED);
  }

  @Test
  void unwrapsWithTheSameEncryptionContext() {
    when(kms.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.builder().plaintext(SdkBytes.fromByteArray(PLAIN)).build());

    assertThat(service.unwrap(WRAPPED, "tok_1")).isEqualTo(PLAIN);

    ArgumentCaptor<DecryptRequest> request = ArgumentCaptor.forClass(DecryptRequest.class);
    verify(kms).decrypt(request.capture());
    assertThat(request.getValue().encryptionContext()).isEqualTo(Map.of("token", "tok_1"));
  }
}
