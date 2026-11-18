package dev.foliopay.service;

import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.model.ReconReport;
import dev.foliopay.domain.model.SettlementLine;
import java.time.LocalDate;
import java.util.List;

/** Revenue assurance: matches captures and PMS postings against an acquirer settlement statement and names every exception. */
public interface ReconciliationService {
  /**
   * The business dates a statement covers. Only captures taken on those dates are expected in it, so yesterday's captures are not reported
   * missing from today's file every day. A capture from another date that the statement does name is still matched and checked. Either
   * end may be open; {@link #ALL} expects every capture ever made (small pilots and demos only).
   */
  record Period(LocalDate from, LocalDate to) {
    public static final Period ALL = new Period(null, null);

    public Period {
      if (from != null && to != null && from.isAfter(to)) {
        throw new InvalidRequestException("The settlement period starts after it ends");
      }
    }

    /** Open ends as dates every database can store, so the query needs no null handling. */
    public LocalDate firstDay() { return from == null ? LocalDate.of(1970, 1, 1) : from; }

    public LocalDate lastDay() { return to == null ? LocalDate.of(9999, 12, 31) : to; }
  }

  ReconReport reconcile(List<SettlementLine> statement, Period period);

  /** Reconciles against a pasted acquirer CSV (psp_ref,amount_minor,currency). A bad row fails loudly. */
  ReconReport reconcileCsv(String csv, Period period);

  /** Reconciles against the acquirer's own statement. {@code distorted} simulates a messy one for demos. */
  ReconReport reconcileAcquirerStatement(boolean distorted, Period period);
}
