package dev.foliopay.api.controller;

import dev.foliopay.api.dto.ReconcileRequest;
import dev.foliopay.domain.model.ReconReport;
import dev.foliopay.service.ReconciliationService;
import dev.foliopay.service.ReconciliationService.Period;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reconciliation", description = "Revenue assurance against an acquirer settlement statement.")
@RestController
@RequestMapping("/api/reconcile")
class ReconciliationController {
  private final ReconciliationService reconciliation;
  private final boolean demoControls;

  ReconciliationController(ReconciliationService reconciliation, @Value("${foliopay.demo-controls:false}") boolean demoControls) {
    this.reconciliation = reconciliation;
    this.demoControls = demoControls;
  }

  @Operation(summary = "Reconcile", description = "With csv (header psp_ref,amount_minor,currency) reconciles that statement; without it, the acquirer's own statement. from/to (business dates, optional) scope which captures are expected. Exceptions: MISSING_IN_SETTLEMENT, AMOUNT_DIFFERENCE, CURRENCY_DIFFERENCE, UNKNOWN_IN_SETTLEMENT, DUPLICATE_IN_SETTLEMENT, NOT_POSTED_TO_PMS.")
  @PostMapping
  ReconReport reconcile(@Valid @RequestBody ReconcileRequest request) {
    Period period = new Period(request.from(), request.to());
    if (request.csv() != null && !request.csv().isBlank()) {
      return reconciliation.reconcileCsv(request.csv(), period);
    }
    return reconciliation.reconcileAcquirerStatement(demoControls && "messy".equals(request.mode()), period);
  }
}
