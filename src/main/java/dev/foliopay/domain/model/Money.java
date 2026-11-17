package dev.foliopay.domain.model;

import dev.foliopay.domain.exception.InvalidRequestException;
import java.util.Currency;
import java.util.regex.Pattern;

/** An amount in minor units (cents) with its ISO 4217 currency. Never floating point. */
public record Money(long minor, String currency) {
  /** Ten to the twelfth minor units: far above any real payment, far below where multiplication overflows a long. */
  public static final long MAX_MINOR = 1_000_000_000_000L;

  private static final Pattern ISO_CURRENCY = Pattern.compile("[A-Z]{3}");

  public Money {
    if (currency == null || !ISO_CURRENCY.matcher(currency).matches()) {
      throw new InvalidRequestException("Currency must be a 3-letter ISO code");
    }
    if (fractionDigits(currency) < 0) {
      throw new InvalidRequestException("Unknown currency");
    }
    if (minor < 0 || minor > MAX_MINOR) {
      throw new InvalidRequestException("Amount is out of range");
    }
  }

  /** Decimal digits of the currency (USD 2, JPY 0, KWD 3), from the JDK's ISO 4217 table; negative when the code is not a currency. */
  public static int fractionDigits(String currency) {
    try {
      return Currency.getInstance(currency).getDefaultFractionDigits();
    } catch (IllegalArgumentException e) {
      return -1;
    }
  }

  /** The amount as a decimal string for systems that take decimals (OPERA): 12345 USD is 123.45, 1234 KWD is 1.234. No floating point. */
  public String toDecimalString() {
    int digits = fractionDigits(currency);
    if (digits == 0) {
      return Long.toString(minor);
    }
    String padded = "0".repeat(Math.max(0, digits + 1 - Long.toString(minor).length())) + minor;
    return padded.substring(0, padded.length() - digits) + "." + padded.substring(padded.length() - digits);
  }

  /** The amount a payment must carry: strictly positive. */
  public Money requirePositive() {
    if (minor == 0) {
      throw new InvalidRequestException("Amount must be positive");
    }
    return this;
  }
}
