package dev.foliopay.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.rule.Luhn;

/** A card number. toString never shows more than the last four digits, so an accidental log line stays safe. */
public record Pan(@JsonIgnore String value) {
  public Pan {
    String digits = value == null ? "" : value.replaceAll("[ -]", "");
    if (!Luhn.valid(digits)) {
      throw new InvalidRequestException("Card number is not valid");
    }
    value = digits;
  }

  public String bin6() {
    return value.substring(0, 6);
  }

  public String last4() {
    return value.substring(value.length() - 4);
  }

  @Override
  public String toString() {
    return "Pan[**** " + last4() + "]";
  }
}
