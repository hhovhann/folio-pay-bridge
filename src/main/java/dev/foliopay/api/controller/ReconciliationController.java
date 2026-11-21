package dev.foliopay.api.controller;

import dev.foliopay.api.ApiPaths;
import dev.foliopay.api.dto.ReconcileRequest;
import dev.foliopay.domain.model.ReconReport;
import dev.foliopay.service.DemoService;
import dev.foliopay.service.ReconciliationService;
import dev.foliopay.service.ReconciliationService.Period;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reconciliation", description = "Revenue assurance against an acquirer settlement statement.")
@RestController
@RequestMapping(ApiPaths.V1 + "/reconcile")
class ReconciliationController {
  private final ReconciliationService reconciliation;
  private final ObjectProvider<DemoService> demo;

  ReconciliationController(ReconciliationService reconciliation, ObjectProvider<DemoService> demo) {
    this.reconciliation = reconciliation;
    this.demo = demo;
  }

  @Operation(summary = "Reconcile", description = "With csv (header psp_ref,amount_minor,currency) reconciles that statement; without it, the"
      + " acquirer's own statement. from/to (business dates, optional) scope which captures are expected. Exceptions: MISSING_IN_SETTLEMENT,"
      + " AMOUNT_DIFFERENCE, CURRENCY_DIFFERENCE, UNKNOWN_IN_SETTLEMENT, DUPLICATE_IN_SETTLEMENT, NOT_POSTED_TO_PMS.")
  @PostMapping
  ReconReport reconcile(@Valid @RequestBody ReconcileRequest request) {
    Period period = new Period(request.from(), request.to());
    if (request.csv() != null && !request.csv().isBlank()) {
      return reconciliation.reconcileCsv(request.csv(), period);
    }
    DemoService demoControls = demo.getIfAvailable(); // present only with the demo controls on: "messy" is a demo, never production
    if ("messy".equals(request.mode()) && demoControls != null) {
      return reconciliation.reconcile(demoControls.messyAcquirerStatement(), period);
    }
    return reconciliation.reconcileAcquirerStatement(period);
  }
}
