package dev.foliopay.domain.model;

import java.util.List;
import java.util.Map;

/**
 * The result of matching our captures and PMS postings against an acquirer statement. Totals are kept per currency (D-1): 100 JPY and
 * 100 USD are not 200 of anything.
 */
public record ReconReport(int matched, Map<String, Integer> exceptions, List<Item> items, Map<String, Total> totals) {
  /** The named reasons a finance team can act on. */
  public enum Category { MISSING_IN_SETTLEMENT, AMOUNT_DIFFERENCE, CURRENCY_DIFFERENCE, UNKNOWN_IN_SETTLEMENT, DUPLICATE_IN_SETTLEMENT,
      NOT_POSTED_TO_PMS }

  /** What we expected and what the acquirer settled for one reference, each with its own currency (they differ for a currency difference). */
  public record Item(Category category, String pspRef, String bookingRef, long expectedMinor, String expectedCurrency, long settledMinor,
      String settledCurrency) {}

  /** Captured and settled amounts in one currency. */
  public record Total(long expectedMinor, long settledMinor) {
    public Total plus(Total other) { return new Total(Math.addExact(expectedMinor, other.expectedMinor),
        Math.addExact(settledMinor, other.settledMinor)); }
  }
}
