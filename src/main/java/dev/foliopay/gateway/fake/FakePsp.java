package dev.foliopay.gateway.fake;

import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.SettlementLine;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.gateway.PspDeclinedException;
import dev.foliopay.gateway.PspTransientException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** In-memory processor with the failure that matters: it captures the money, then the response is lost. Synthetic; no network. Wired only when foliopay.gateway=fake. */
@Component
@ConditionalOnProperty(name = "foliopay.gateway", havingValue = "fake")
public class FakePsp implements PspClient {
  /** LOSE_CAPTURE_REPLIES stays armed: every capture happens and its reply is lost, so retries run out with the money taken (D-27). */
  public enum Fault { NONE, TIMEOUT_AFTER_CAPTURE, DECLINE_NEXT, DECLINE_CAPTURE_NEXT, LOSE_CAPTURE_REPLIES }

  private static final Logger log = LoggerFactory.getLogger(FakePsp.class);
  private final Map<String, PspResult> resultsByKey = new ConcurrentHashMap<>();
  private final Map<String, Money> settled = new ConcurrentHashMap<>();
  private final AtomicInteger voids = new AtomicInteger();
  private final AtomicInteger captures = new AtomicInteger();
  private final AtomicLong capturedMinor = new AtomicLong();
  private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);

  public void arm(Fault f) { fault.set(f); }
  public Fault armed() { return fault.get(); }
  public int captureCount() { return captures.get(); }
  public long capturedTotalMinor() { return capturedMinor.get(); }
  public int voidCount() { return voids.get(); }

  public void reset() {
    settled.clear();
    resultsByKey.clear();
    captures.set(0);
    voids.set(0);
    capturedMinor.set(0);
    fault.set(Fault.NONE);
  }

  @Override
  public PspResult authorize(String key, Pan pan, String expiry, Money amount) {
    if (fault.compareAndSet(Fault.DECLINE_NEXT, Fault.NONE)) {
      throw new PspDeclinedException("Do not honor");
    }
    return resultsByKey.computeIfAbsent(key, k -> new PspResult("auth_" + shortId()));
  }

  @Override
  public PspResult capture(String key, String authRef, Money amount) {
    if (fault.compareAndSet(Fault.DECLINE_CAPTURE_NEXT, Fault.NONE)) {
      throw new PspDeclinedException("Capture declined");
    }
    boolean[] first = {false};
    PspResult result =
        resultsByKey.computeIfAbsent(
            key,
            k -> {
              first[0] = true;
              captures.incrementAndGet();
              capturedMinor.addAndGet(amount.minor());
              PspResult created = new PspResult("cap_" + shortId());
              settled.put(created.ref(), amount);
              return created;
            });
    log.info("PSP capture {} {} (new={})", result.ref(), amount.minor(), first[0]);
    if (fault.compareAndSet(Fault.TIMEOUT_AFTER_CAPTURE, Fault.NONE) || fault.get() == Fault.LOSE_CAPTURE_REPLIES) {
      throw new PspTransientException("Read timed out after capture");
    }
    return result;
  }

  @Override
  public void voidAuthorization(String key, String authRef) {
    voids.incrementAndGet();
    log.info("PSP void {}", authRef);
  }

  @Override
  public List<SettlementLine> settlementStatement() {
    return settled.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
        .map(e -> new SettlementLine(e.getKey(), e.getValue().minor(), e.getValue().currency()))
        .toList();
  }

  private static String shortId() { return UUID.randomUUID().toString().substring(0, 8); }
}
