package dev.foliopay.domain.model;

import java.util.List;
import java.util.Map;

/** The result of matching our captures and PMS postings against an acquirer statement. */
public record ReconReport(int matched, Map<String, Integer> exceptions, List<Item> items, long expectedMinor, long settledMinor) {
  /** The named reasons a finance team can act on. */
  public enum Category { MISSING_IN_SETTLEMENT, AMOUNT_DIFFERENCE, CURRENCY_DIFFERENCE, UNKNOWN_IN_SETTLEMENT, DUPLICATE_IN_SETTLEMENT, NOT_POSTED_TO_PMS }

  public record Item(Category category, String pspRef, String bookingRef, long expectedMinor, long settledMinor) {}

  public long differenceMinor() { return settledMinor - expectedMinor; }
}
