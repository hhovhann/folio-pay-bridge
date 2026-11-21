package dev.foliopay.domain.model;

import java.time.Duration;

/** How often a webhook is tried, and how long to wait between tries: twice as long after each failure, never longer than the longest wait. */
public record DeliverySchedule(int maxAttempts, Duration firstRetry, Duration longestWait) {
  private static final int MAX_DOUBLINGS = 20;

  /** The wait after the given number of failed attempts (1 for the first). */
  public Duration waitAfter(int failedAttempts) {
    Duration wait = firstRetry.multipliedBy(1L << Math.min(Math.max(failedAttempts - 1, 0), MAX_DOUBLINGS));
    return wait.compareTo(longestWait) > 0 ? longestWait : wait;
  }
}
