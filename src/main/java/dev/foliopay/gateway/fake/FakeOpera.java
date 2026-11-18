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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in for OPERA with an outage switch and the lost-response case. Reservation F-CLOSED is always rejected, and a payment without a
 * payment method or cashier is rejected as OPERA would. Like OPERA it posts on its own business date. Like OPERA (D-23) it does not deduplicate: every call is a new folio entry and is
 * counted, so a test sees a second post of the same payment. {@code find} returns the first entry with a reference.
 * Synthetic; no network. Wired only when foliopay.gateway=fake.
 */
@Component
@ConditionalOnProperty(name = "foliopay.gateway", havingValue = "fake")
public class FakeOpera implements OperaClient {
  public enum Fault { NONE, DOWN, TIMEOUT_AFTER_POST }

  private static final String CLOSED_FOLIO = "F-CLOSED";
  private static final Logger log = LoggerFactory.getLogger(FakeOpera.class);
  private final Map<String, Posted> posts = new ConcurrentHashMap<>();
  private final AtomicInteger postCount = new AtomicInteger();
  private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
  private final AtomicReference<LocalDate> businessDate = new AtomicReference<>();
  private final Clock clock;

  public FakeOpera(Clock clock) {
    this.clock = clock;
  }

  public void arm(Fault f) { fault.set(f); }
  public Fault armed() { return fault.get(); }
  /** How many times a payment was posted to a folio: every call that reached the ledger, not the number of distinct references. */
  public int postCount() { return postCount.get(); }
  public void setBusinessDate(LocalDate date) { businessDate.set(date); }

  public void reset() {
    posts.clear();
    postCount.set(0);
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
    if (p.paymentMethod() == null || p.paymentMethod().isBlank() || p.cashierId() <= 0) {
      throw new OperaRejectedException("Payment method and cashier are required");
    }
    Posted posted = new Posted("OP-" + UUID.randomUUID().toString().substring(0, 8), p.reservationId(), p.amount(), businessDate(p.hotelId()));
    posts.putIfAbsent(p.externalRef(), posted);
    postCount.incrementAndGet();
    log.info("OPERA post {} reservation {} {} {} window {} (ref {})", posted.operaRef(), p.reservationId(), p.amount().toDecimalString(), p.amount().currency(), p.folioWindow(), p.externalRef());
    if (fault.compareAndSet(Fault.TIMEOUT_AFTER_POST, Fault.NONE)) {
      throw new OperaUnavailableException("Read timed out after post");
    }
    return posted.operaRef();
  }

  @Override
  public Optional<Posted> find(String hotelId, String reservationId, String externalRef) {
    return Optional.ofNullable(posts.get(externalRef)).filter(posted -> posted.reservationId().equals(reservationId));
  }

  @Override
  public LocalDate businessDate(String hotelId) {
    LocalDate set = businessDate.get();
    return set != null ? set : LocalDate.now(clock);
  }
}
