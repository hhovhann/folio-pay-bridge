package dev.foliopaysim.psp;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A card processor, in memory. It deduplicates on the Idempotency-Key header the way a real one does, and it can capture the money and then
 * lose the response. The card number is used only to decide approve or decline: it is never stored, and only the last four digits are kept.
 */
@Component
@Profile("psp")
public class PspSimulator {
  public enum Fault { NONE, DECLINE_NEXT, DECLINE_CAPTURE_NEXT, TIMEOUT_AFTER_CAPTURE }

  /** {@code authRef} is the authorisation a capture or a void acts on; empty for an authorisation. */
  public record Entry(String ref, String kind, String last4, long amountMinor, String currency, String state, String authRef, Instant at) {}

  private final Clock clock;
  private final Map<String, Entry> byIdempotencyKey = new ConcurrentHashMap<>();
  private final List<Entry> ledger = new ArrayList<>();
  private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);

  PspSimulator(Clock clock) { this.clock = clock; }

  public void arm(Fault f) { fault.set(f); }
  public boolean consume(Fault f) { return fault.compareAndSet(f, Fault.NONE); }

  /** The first call with a key does the work; a repeat with the same key returns the same result and does nothing. */
  public synchronized Entry once(String idempotencyKey, java.util.function.Supplier<Entry> work) {
    Entry existing = byIdempotencyKey.get(idempotencyKey);
    if (existing != null) return existing;
    Entry created = work.get();
    byIdempotencyKey.put(idempotencyKey, created);
    ledger.add(created);
    return created;
  }

  public Entry entry(String kind, String last4, long amountMinor, String currency, String state, String authRef) {
    String prefix = switch (kind) { case "AUTH" -> "auth_"; case "CAPTURE" -> "cap_"; default -> "void_"; };
    return new Entry(prefix + UUID.randomUUID().toString().substring(0, 8), kind, last4, amountMinor, currency, state, authRef, Instant.now(clock));
  }

  /** The answer already given for a key, if any: a repeat gets it back before any fault is applied, as a real processor would. */
  public synchronized Optional<Entry> seen(String idempotencyKey) { return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey)); }

  /** The authorisation a capture or void names, so acting on a wrong or unknown one is refused instead of silently accepted. */
  public synchronized Optional<Entry> authorisation(String ref) {
    return ledger.stream().filter(e -> e.kind().equals("AUTH") && e.ref().equals(ref)).findFirst();
  }

  /** What the acquirer would settle: captures only. */
  public synchronized List<Entry> settlement() { return ledger.stream().filter(e -> e.kind().equals("CAPTURE")).toList(); }

  public synchronized Map<String, Object> state() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("fault", fault.get().name());
    state.put("ledger", List.copyOf(ledger));
    state.put("capturedMinor", ledger.stream().filter(e -> e.kind().equals("CAPTURE")).mapToLong(Entry::amountMinor).sum());
    return state;
  }

  public synchronized void reset() {
    byIdempotencyKey.clear();
    ledger.clear();
    fault.set(Fault.NONE);
  }
}
