package dev.foliopay.crypto;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/** Production key service: AES-256 data keys generated and wrapped by AWS KMS, with the token id as the encryption context (audited in CloudTrail). */
@Component
@ConditionalOnProperty(name = "foliopay.keys", havingValue = "kms")
public class KmsKeyService implements KeyService {
    private final KmsClient kms;
    private final String keyId;

    public KmsKeyService(KmsClient kms, @Value("${foliopay.kms.key-id}") String keyId) {
        this.kms = kms;
        this.keyId = keyId;
    }

    @Override
    public DataKey newDataKey(String context) {
        GenerateDataKeyResponse r = kms.generateDataKey(GenerateDataKeyRequest.builder()
                .keyId(keyId).keySpec(DataKeySpec.AES_256).encryptionContext(Map.of("token", context)).build());
        return new DataKey(r.plaintext().asByteArray(), r.ciphertextBlob().asByteArray());
    }

    @Override
    public byte[] unwrap(byte[] wrapped, String context) {
        return kms.decrypt(DecryptRequest.builder().keyId(keyId).ciphertextBlob(SdkBytes.fromByteArray(wrapped))
                .encryptionContext(Map.of("token", context)).build()).plaintext().asByteArray();
    }

    @Override
    public String keyId() { return keyId; }
}
