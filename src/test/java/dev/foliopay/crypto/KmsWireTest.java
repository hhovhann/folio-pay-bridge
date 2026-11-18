package dev.foliopay.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * The real KMS client, with the HTTP library this build resolves, against a local endpoint that answers like KMS. `KmsKeyServiceTest`
 * stubs the client, so without this test a version of the HTTP library the AWS SDK cannot run on would first fail in production, at the
 * first card (agent log 50). It is not AWS: the replies are canned, the request is checked only for what the adapter decides.
 */
class KmsWireTest {
  private static final String KEY_ARN = "arn:aws:kms:eu-west-1:111122223333:key/1111aaaa-2222-bbbb-3333-cccc4444dddd";
  private static final byte[] PLAIN = new byte[32];
  private static final byte[] WRAPPED = {7, 7, 7};
  private final List<String> targets = new CopyOnWriteArrayList<>();
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private HttpServer kms;

  @BeforeEach
  void start() throws Exception {
    kms = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    kms.createContext("/", exchange -> {
      String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
      targets.add(target);
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      Base64.Encoder b64 = Base64.getEncoder();
      String reply = target.endsWith("GenerateDataKey")
          ? "{\"KeyId\":\"" + KEY_ARN + "\",\"Plaintext\":\"" + b64.encodeToString(PLAIN) + "\",\"CiphertextBlob\":\"" + b64.encodeToString(WRAPPED) + "\"}"
          : "{\"KeyId\":\"" + KEY_ARN + "\",\"Plaintext\":\"" + b64.encodeToString(PLAIN) + "\"}";
      byte[] body = reply.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.1");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    kms.start();
  }

  @AfterEach
  void stop() {
    kms.stop(0);
  }

  /** Fails if the SDK's HTTP client cannot run on the HTTP library versions this build resolves, or if the wire calls change. */
  @Test
  void theRealClientWrapsAndUnwrapsADataKeyOverHttp() {
    try (KmsClient client = KmsClient.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + kms.getAddress().getPort()))
        .region(Region.EU_WEST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDTESTONLY", "test-secret-not-real")))
        .build()) {
      KmsKeyService service = new KmsKeyService(client, "alias/cards");

      DataKey key = service.newDataKey("tok_1");
      byte[] plain = service.unwrap(key.wrapped(), "tok_1", key.keyId());

      assertThat(key.wrapped()).isEqualTo(WRAPPED);
      assertThat(key.keyId()).isEqualTo(KEY_ARN);
      assertThat(plain).hasSize(32);
      assertThat(targets).containsExactly("TrentService.GenerateDataKey", "TrentService.Decrypt");
      assertThat(bodies.get(0)).contains("\"KeyId\":\"alias/cards\"").contains("\"KeySpec\":\"AES_256\"").contains("\"token\":\"tok_1\"");
      assertThat(bodies.get(1)).as("decrypt names the key that wrapped it, not the alias (D-31)").contains(KEY_ARN).contains("\"token\":\"tok_1\"");
    }
  }
}
