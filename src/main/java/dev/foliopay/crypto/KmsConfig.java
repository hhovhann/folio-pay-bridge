package dev.foliopay.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.kms.KmsClient;

@Configuration
@ConditionalOnProperty(name = "foliopay.keys", havingValue = "kms")
class KmsConfig {
    /** Region and credentials come from the default AWS chain (instance role on EC2, task role on ECS). No keys in config. */
    @Bean
    KmsClient kmsClient() { return KmsClient.create(); }
}
