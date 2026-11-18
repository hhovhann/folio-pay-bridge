package dev.foliopay.service;

import java.time.Instant;
import java.util.List;

/**
 * What an operator needs at a glance, computed by the database (counts and sums, never by paging through lists): money captured today
 * per currency, and every queue that needs a person: payments still pending, postings waiting for OPERA or rejected by it, webhooks that
 * failed, payment links waiting to be paid.
 */
public interface OverviewService {
  /** Captured since the start of the hotel's day, in one currency: amounts are never added across currencies (D-1). */
  record Captured(String currency, long amountMinor, long count) {}

  record Overview(
      Instant at,
      List<Captured> capturedToday,
      long chargesPending,
      long postingsPending,
      long deadLettersReplayable,
      long deadLettersRejected,
      long webhooksPending,
      long webhooksFailed,
      long linksOpen,
      long linksPaying) {}

  Overview overview();
}
