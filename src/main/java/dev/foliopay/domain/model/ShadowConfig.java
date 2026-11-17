package dev.foliopay.domain.model;

/** The two places where a rebuilt system quietly differs from the old one: how tax is rounded, and which calendar day a payment belongs to. */
public record ShadowConfig(TaxRounding rounding, DateRule dateRule) {
  public enum TaxRounding { PER_NIGHT, ON_TOTAL }

  public enum DateRule { UTC_DAY, HOTEL_LOCAL_DAY }

  public static ShadowConfig strictNew() { return new ShadowConfig(TaxRounding.ON_TOTAL, DateRule.UTC_DAY); }

  public static ShadowConfig legacyCompatible() { return new ShadowConfig(TaxRounding.PER_NIGHT, DateRule.HOTEL_LOCAL_DAY); }
}
