package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Review finding: the posting lease was a fixed two minutes whatever the timeouts and retries were set to. */
@DisplayName("Posting lease: derived from the timeouts and the retries")
class ResilienceConfigTest {
  private static final RetryProperties DEFAULTS = new RetryProperties(3, Duration.ofMillis(200), 2.0, 0.5);

  @Test
  @DisplayName("The posting lease covers the worst case of one attempt with the default settings")
  void theLeaseCoversTheWorstCaseOfOneAttemptWithTheDefaultSettings() {
    Duration lease = ResilienceConfig.postingLease(DEFAULTS, Duration.ofSeconds(5)); // 2 s connect + 3 s read
    Duration worstAttempt = Duration.ofSeconds(5).multipliedBy(3 * 8);
    assertThat(lease).isGreaterThan(worstAttempt.multipliedBy(2));
  }

  /** Fails if the lease does not grow with the settings: a slow attempt would outlive it and a replay would post a second time. */
  @Test
  @DisplayName("Slower timeouts and more retries give a longer lease")
  void slowerTimeoutsAndMoreRetriesGiveALongerLease() {
    RetryProperties patient = new RetryProperties(5, Duration.ofMillis(200), 2.0, 0.5);
    Duration lease = ResilienceConfig.postingLease(patient, Duration.ofSeconds(62)); // 2 s connect + 60 s read
    assertThat(lease).isGreaterThan(Duration.ofSeconds(62).multipliedBy(5 * 8));
  }

  @Test
  @DisplayName("The lease is never shorter than a minute")
  void theLeaseIsNeverShorterThanAMinute() {
    assertThat(ResilienceConfig.postingLease(new RetryProperties(1, Duration.ZERO, 1.0, 0), Duration.ofMillis(10))).isEqualTo(Duration.ofMinutes(1));
  }
}
