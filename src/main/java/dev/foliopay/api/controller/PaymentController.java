package dev.foliopay.api.controller;

import dev.foliopay.api.dto.ChargeView;
import dev.foliopay.api.dto.PayRequest;
import dev.foliopay.api.dto.PaymentResponse;
import dev.foliopay.api.dto.PostingView;
import dev.foliopay.service.ChargeService;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PostingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import dev.foliopay.service.BusinessDateService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments", description = "Charge a tokenised card, post to the folio, inspect the ledger, replay dead-lettered postings.")
@RestController
@RequestMapping("/api")
class PaymentController {
  private final PaymentService payments;
  private final ChargeService charges;
  private final PostingService postings;
  private final BusinessDateService businessDates;

  PaymentController(PaymentService payments, ChargeService charges, PostingService postings, BusinessDateService businessDates) {
    this.payments = payments;
    this.charges = charges;
    this.postings = postings;
    this.businessDates = businessDates;
  }

  @Operation(summary = "Pay", description = "Idempotent on idempotencyKey: repeating a request returns the first result and never charges twice. The same key with different parameters is 409. The outcome says which half happened: OK, DECLINED, CHARGE_PENDING (retry with the same key), CHARGED_NOT_POSTED (captured, waiting in the dead-letter queue).")
  @PostMapping("/payments")
  PaymentResponse pay(@Valid @RequestBody PayRequest request) {
    return PaymentResponse.from(payments.pay(request.toCommand(businessDates.resolve(request.businessDate()))));
  }

  @Operation(summary = "List charges")
  @GetMapping("/charges")
  List<ChargeView> charges() { return charges.findAll().stream().map(ChargeView::from).toList(); }

  @Operation(summary = "List folio postings")
  @GetMapping("/postings")
  List<PostingView> postings() { return postings.findAll().stream().map(PostingView::from).toList(); }

  @Operation(summary = "Replay a posting", description = "Tries a dead-lettered posting again. A posting rejected by the PMS is returned untouched; an outage can be replayed. Safe to repeat.")
  @PostMapping("/postings/{id}/replay")
  PostingView replay(@PathVariable String id) { return PostingView.from(postings.replay(id)); }
}
