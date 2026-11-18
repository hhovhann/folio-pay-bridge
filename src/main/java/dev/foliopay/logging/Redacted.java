package dev.foliopay.logging;

/**
 * An exception fit for a log: the class and the stack trace of every exception in the cause chain, and none of their messages. A message
 * can carry request data (a card number a parser echoed, one the Luhn mask cannot recognise because it was mistyped or split), so it is
 * dropped, not masked (CLAUDE.md rule 1). Where the failure happened is kept, which is what an on-call engineer needs; what was in the
 * request is not. Pair it with the request id in the log line to find the request.
 */
public final class Redacted extends RuntimeException {
  private static final int MAX_DEPTH = 10;

  private Redacted(Throwable original, Throwable cause) {
    super(original.getClass().getName(), cause, false, true);
    setStackTrace(original.getStackTrace());
  }

  public static Throwable of(Throwable e) {
    return of(e, 0);
  }

  private static Throwable of(Throwable e, int depth) {
    if (e == null || depth >= MAX_DEPTH) {
      return null;
    }
    Throwable cause = e.getCause() == e ? null : e.getCause();
    return new Redacted(e, of(cause, depth + 1));
  }

  @Override
  public String toString() { return getMessage() + " (message withheld)"; }
}
