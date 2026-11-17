package dev.foliopay.service;

import java.time.LocalDate;

/** The hotel's business date, which comes from the PMS (it moves at night audit), not from the wall clock. */
public interface BusinessDateService {
  /** The PMS business date when {@code requested} is null; otherwise {@code requested} if it equals the PMS date, else a 400. */
  LocalDate resolve(LocalDate requested);
}
