package dev.foliopay.service;

import dev.foliopay.domain.model.ReconReport;
import dev.foliopay.domain.model.SettlementLine;
import java.util.List;

/** Revenue assurance: matches captures and PMS postings against an acquirer settlement statement and names every exception. */
public interface ReconciliationService {
  ReconReport reconcile(List<SettlementLine> statement);

  /** Reconciles against a pasted acquirer CSV (psp_ref,amount_minor,currency). A bad row fails loudly. */
  ReconReport reconcileCsv(String csv);

  /** Reconciles against the acquirer's own statement. {@code distorted} simulates a messy one for demos. */
  ReconReport reconcileAcquirerStatement(boolean distorted);
}
