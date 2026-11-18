package dev.foliopay.domain.rule;

/** The Luhn check digit test used to recognise a plausible card number. */
public final class Luhn {
  private static final int MIN_DIGITS = 13;
  private static final int MAX_DIGITS = 19;

  private Luhn() {}

  public static boolean valid(String digits) {
    if (digits == null || digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS) {
      return false;
    }
    int sum = 0;
    boolean doubleIt = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      char c = digits.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
      int d = c - '0';
      if (doubleIt) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      doubleIt = !doubleIt;
    }
    return sum % 10 == 0;
  }
}
