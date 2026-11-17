package dev.foliopay.domain.model;

import java.util.List;
import java.util.Map;

public record ShadowReport(
    String runId, ShadowConfig config, int bookings, int matched, int mismatched, Map<String, Integer> byField, List<Mismatch> samples, int errors) {
  public record Mismatch(String bookingRef, String field, String legacy, String candidate) {}

  public double matchRate() { return bookings == 0 ? 1.0 : (double) matched / bookings; }
}
