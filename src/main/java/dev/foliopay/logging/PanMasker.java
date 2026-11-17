package dev.foliopay.logging;

import dev.foliopay.domain.rule.Luhn;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defence in depth: the code never logs a card number, and this masks any Luhn-valid 13 to 19 digit sequence that slips through. It looks
 * inside every run of digits joined by up to two separator characters (spaces, dashes, dots, slashes, any Unicode digit), so a number
 * with other digits glued to either side is still found.
 */
public final class PanMasker {
  private static final int MIN_PAN = 13;
  private static final int MAX_PAN = 19;
  private static final int VISIBLE = 4;
  private static final Pattern RUN = Pattern.compile("\\p{Nd}(?:[^\\p{L}\\p{N}\\r\\n]{0,2}\\p{Nd}){" + (MIN_PAN - 1) + ",}");

  private PanMasker() {}

  public static String mask(String text) {
    if (text == null || text.length() < MIN_PAN) {
      return text;
    }
    Matcher runs = RUN.matcher(text);
    StringBuilder out = new StringBuilder();
    while (runs.find()) {
      runs.appendReplacement(out, Matcher.quoteReplacement(maskRun(runs.group())));
    }
    runs.appendTail(out);
    return out.toString();
  }

  /** Masks, then makes the text one line: a value with a line break cannot forge a second log entry. */
  public static String safe(String text) {
    String masked = mask(text);
    return masked == null ? null : masked.replace("\r", "\\r").replace("\n", "\\n");
  }

  private static String maskRun(String run) {
    char[] chars = run.toCharArray();
    int[] at = new int[chars.length];
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < chars.length; i++) {
      if (Character.isDigit(chars[i])) {
        at[digits.length()] = i;
        digits.append(Character.forDigit(Character.digit(chars[i], 10), 10));
      }
    }
    int n = digits.length();
    // Every Luhn-valid window of 13 to 19 digits hides all but its last four digits. The union of the windows is masked, so two numbers
    // back to back, or a number with other digits glued on, cannot leave a digit visible; over-masking is the safe way to be wrong.
    boolean[] hidden = new boolean[n];
    for (int start = 0; start + MIN_PAN <= n; start++) {
      for (int len = MIN_PAN; len <= MAX_PAN && start + len <= n; len++) {
        if (Luhn.valid(digits.substring(start, start + len))) {
          for (int k = start; k < start + len - VISIBLE; k++) {
            hidden[k] = true;
          }
        }
      }
    }
    for (int k = 0; k < n; k++) {
      if (hidden[k]) {
        chars[at[k]] = '*';
      }
    }
    return new String(chars);
  }
}
