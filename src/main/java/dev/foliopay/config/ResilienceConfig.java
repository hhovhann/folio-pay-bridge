package dev.foliopay.config;

import dev.foliopay.gateway.OperaUnavailableException;
import dev.foliopay.gateway.PspTransientException;
import dev.foliopay.gateway.http.HttpGatewayProperties;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
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
  private static final Duration MIN_LEASE = Duration.ofMinutes(1);

  @Bean
  Retry pspRetry(RetryProperties props) {
    return build("psp", PspTransientException.class, props);
  }

  @Bean
  Retry folioRetry(RetryProperties props) {
    return build("folio", OperaUnavailableException.class, props);
  }

  /**
   * How long one request may talk to OPERA about a posting before another may take over. It covers the worst case of one attempt with the
   * configured settings, doubled: every try is a look and a post, each of which may fetch a token, be refused with 401, fetch again and
   * resend (four exchanges, each bounded by the connect and read timeouts), plus the backoff between tries. Never under a minute.
   */
  @Bean
  Duration postingLease(RetryProperties retry, HttpGatewayProperties http) {
    return postingLease(retry, http.connectTimeout().plus(http.readTimeout()));
  }

  static Duration postingLease(RetryProperties retry, Duration exchange) {
    int exchangesPerTry = 2 * 4;
    Duration worst = exchange.multipliedBy((long) exchangesPerTry * retry.maxAttempts());
    double backoffMillis = 0;
    for (int i = 0; i < retry.maxAttempts() - 1; i++) {
      backoffMillis += retry.initialBackoff().toMillis() * Math.pow(retry.multiplier(), i) * (1 + retry.jitter());
    }
    Duration lease = worst.plusMillis((long) Math.ceil(backoffMillis)).multipliedBy(2);
    return lease.compareTo(MIN_LEASE) < 0 ? MIN_LEASE : lease;
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
