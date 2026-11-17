package dev.foliopay.gateway;

import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.SettlementLine;
import java.util.List;

/** Card processor port, so the acquirer can be swapped. Every call takes an idempotency key: the same key returns the same result and never charges twice. */
public interface PspClient {
  PspResult authorize(String idempotencyKey, Pan pan, String expiry, Money amount);

  PspResult capture(String idempotencyKey, String authRef, Money amount);

  /** Releases an authorisation hold that will not be captured, so a virtual card loaded for one amount is not left short. */
  void voidAuthorization(String idempotencyKey, String authRef);

  /** What the acquirer says it settled: capture reference, amount and currency. */
  List<SettlementLine> settlementStatement();

  record PspResult(String ref) {}
}
