package dev.foliopay.service;

import java.time.LocalDate;

/** The hotel's business date, which comes from the PMS (it moves at night audit), not from the wall clock. */
public interface BusinessDateService {
  /** The PMS business date when {@code requested} is null; otherwise {@code requested} if it equals the PMS date, else a 400. */
  LocalDate resolve(LocalDate requested);

  /**
   * A date a client sent is checked against the PMS; no date stays null, so the charge service decides: the PMS date for a new charge, the
   * stored date for a repeat. That is what lets a charge left pending before night audit be resumed after it.
   */
  default LocalDate checkRequested(LocalDate requested) {
    return requested == null ? null : resolve(requested);
  }
}
