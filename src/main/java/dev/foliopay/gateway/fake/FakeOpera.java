package dev.foliopay.gateway.fake;

import dev.foliopay.gateway.OperaClient;
import dev.foliopay.gateway.OperaRejectedException;
import dev.foliopay.gateway.OperaUnavailableException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in for OPERA with an outage switch and the lost-response case. Reservation F-CLOSED is always rejected, and a payment without a
 * payment code or cashier is rejected as OPERA would. It deduplicates on the external reference (a real OPERA may not: callers look first).
 * Synthetic; no network. Wired only when foliopay.gateway=fake.
 */
@Component
@ConditionalOnProperty(name = "foliopay.gateway", havingValue = "fake")
public class FakeOpera implements OperaClient {
  public enum Fault { NONE, DOWN, TIMEOUT_AFTER_POST }

  private static final String CLOSED_FOLIO = "F-CLOSED";
  private static final Logger log = LoggerFactory.getLogger(FakeOpera.class);
  private final Map<String, Posted> posts = new ConcurrentHashMap<>();
  private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
  private final AtomicReference<LocalDate> businessDate = new AtomicReference<>();
  private final Clock clock;

  public FakeOpera(Clock clock) {
    this.clock = clock;
  }

  public void arm(Fault f) { fault.set(f); }
  public Fault armed() { return fault.get(); }
  public int postCount() { return posts.size(); }
  public void setBusinessDate(LocalDate date) { businessDate.set(date); }

  public void reset() {
    posts.clear();
    fault.set(Fault.NONE);
    businessDate.set(null);
  }

  @Override
  public String postPayment(OperaPayment p) {
    if (fault.get() == Fault.DOWN) {
      throw new OperaUnavailableException("OPERA unreachable (503)");
    }
    if (CLOSED_FOLIO.equals(p.reservationId())) {
      throw new OperaRejectedException("Folio is closed");
    }
    if (p.paymentCode() == null || p.paymentCode().isBlank() || p.cashierId() == null || p.cashierId().isBlank()) {
      throw new OperaRejectedException("Payment code and cashier are required");
    }
    Posted posted = posts.computeIfAbsent(p.externalRef(), r -> new Posted("OP-" + UUID.randomUUID().toString().substring(0, 8), p.reservationId(), p.amount(), p.businessDate()));
    log.info("OPERA post {} reservation {} {} {} window {} (ref {})", posted.operaRef(), p.reservationId(), p.amount().toDecimalString(), p.amount().currency(), p.folioWindow(), p.externalRef());
    if (fault.compareAndSet(Fault.TIMEOUT_AFTER_POST, Fault.NONE)) {
      throw new OperaUnavailableException("Read timed out after post");
    }
    return posted.operaRef();
  }

  @Override
  public Optional<Posted> find(String hotelId, String externalRef) { return Optional.ofNullable(posts.get(externalRef)); }

  @Override
  public LocalDate businessDate(String hotelId) {
    LocalDate set = businessDate.get();
    return set != null ? set : LocalDate.now(clock);
  }
}
