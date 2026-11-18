package dev.foliopay.domain.rule;

import dev.foliopay.domain.model.ShadowBooking;
import dev.foliopay.domain.model.ShadowConfig;
import java.time.LocalDate;
import java.time.ZoneId;

/** Folio amount and business date rules, shared by the new service and the legacy stand-in so each can be run under either rule. */
public final class Pricing {
  private static final int BASIS_POINTS = 10_000;

  private Pricing() {}

  public static long total(ShadowBooking b, ShadowConfig.TaxRounding mode) {
    long room = Math.multiplyExact((long) b.nights(), b.rateMinor());
    long tax =
        mode == ShadowConfig.TaxRounding.PER_NIGHT
            ? Math.multiplyExact((long) b.nights(), halfUp(Math.multiplyExact(b.rateMinor(), (long) b.taxBps())))
            : halfUp(Math.multiplyExact(room, (long) b.taxBps()));
    return Math.addExact(room, tax);
  }

  public static LocalDate businessDate(ShadowBooking b, ShadowConfig.DateRule rule) {
    ZoneId zone = rule == ShadowConfig.DateRule.UTC_DAY ? ZoneId.of("UTC") : b.zone();
    return b.at().atZone(zone).toLocalDate();
  }

  private static long halfUp(long basisPointsProduct) {
    return (basisPointsProduct + BASIS_POINTS / 2) / BASIS_POINTS;
  }
}
