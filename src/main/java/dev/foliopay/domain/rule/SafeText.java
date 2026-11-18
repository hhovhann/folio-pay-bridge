package dev.foliopay.domain.rule;

/**
 * Text that came from outside (processor and PMS messages, client ids, payment request descriptions) made safe to store, log and show: no
 * control characters, no invisible formatting characters, bounded length. Invisible characters (bidirectional overrides, zero-width
 * characters, Unicode tag characters) are how text shows a person one thing and a program, or a language model reading it, another: a
 * reversed amount on a dashboard, or hidden instructions in a description that a support assistant later reads (D-24).
 */
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
    String flat = text.replaceAll("\\p{Cf}+", "").replaceAll("[\\p{Cntrl}\\u2028\\u2029\\u0085]+", " ").strip();
    return flat.length() <= max ? flat : flat.substring(0, max - 3) + "...";
  }
}
