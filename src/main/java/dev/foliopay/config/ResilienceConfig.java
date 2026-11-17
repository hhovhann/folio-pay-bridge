package dev.foliopay.config;

import dev.foliopay.gateway.OperaUnavailableException;
import dev.foliopay.gateway.PspTransientException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One Resilience4j retry per downstream system. Only the exceptions that mean "the call may not have arrived" are retried, so a decline or a
 * rejection fails at once. Retrying is safe because every call carries an idempotency key.
 */
@Configuration
class ResilienceConfig {
  private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

  @Bean
  Retry pspRetry(RetryProperties props) {
    return build("psp", PspTransientException.class, props);
  }

  @Bean
  Retry folioRetry(RetryProperties props) {
    return build("folio", OperaUnavailableException.class, props);
  }

  private static Retry build(String name, Class<? extends Throwable> transientType, RetryProperties props) {
    RetryConfig config =
        RetryConfig.custom()
            .maxAttempts(props.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(props.initialBackoff(), props.multiplier(), props.jitter()))
            .retryExceptions(transientType)
            .build();
    Retry retry = Retry.of(name, config);
    retry.getEventPublisher()
        .onRetry(e -> log.warn("Retrying {} call, attempt {} failed: {}", name, e.getNumberOfRetryAttempts(), e.getLastThrowable().getMessage()));
    return retry;
  }
}
