package dev.foliopay.domain.rule;

/** Text that came from outside (processor and PMS messages, client ids) made safe to store, log and show: no control characters, bounded length. */
public final class SafeText {
  public static final int MAX_STORED = 255;

  private SafeText() {}

  public static String clean(String text) {
    return clean(text, MAX_STORED);
  }

  public static String clean(String text, int max) {
    if (text == null) {
      return null;
    }
    String flat = text.replaceAll("[\\p{Cntrl}\\u2028\\u2029\\u0085]+", " ").strip();
    return flat.length() <= max ? flat : flat.substring(0, max - 3) + "...";
  }
}
