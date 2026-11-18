package dev.foliopay.domain.rule;

import dev.foliopay.domain.model.ShadowBooking;
import dev.foliopay.domain.model.ShadowConfig;
import java.time.LocalDate;

/**
 * What the old system does, as the team describes it: tax rounded each night, payment dated in the hotel's local day. In real life this is
 * legacy code read as a specification.
 */
public final class LegacyRules {
  public record Expected(long amountMinor, LocalDate businessDate) {}

  private LegacyRules() {}

  public static Expected expected(ShadowBooking b) {
    return new Expected(
        Pricing.total(b, ShadowConfig.TaxRounding.PER_NIGHT), Pricing.businessDate(b, ShadowConfig.DateRule.HOTEL_LOCAL_DAY));
  }
}
