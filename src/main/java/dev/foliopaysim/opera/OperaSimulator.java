package dev.foliopaysim.opera;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * An OPERA-style property management system, in memory. It behaves the way a real one does where it matters to a payment bridge: access
 * tokens expire, the business date moves only at night audit, a closed reservation refuses payments, it does NOT deduplicate (so a caller
 * that is unsure must look first), and it can be made to fail in the ways a real one fails.
 */
@Component
@Profile("opera")
public class OperaSimulator {
  public enum Fault { NONE, DOWN, THROTTLE_NEXT, LOCK_CONFLICT_NEXT, TIMEOUT_AFTER_POST }

  public record Payment(String transactionNo, String hotelId, String reservationId, int folioWindow, String paymentCode, String cashierId,
                        String amount, String currency, LocalDate businessDate, String reference, String pspRef, Instant postedAt) {}

  public record Token(String value, Instant expiresAt) {}

  private final Clock clock;
  private final long tokenTtlSeconds;
  private final String appKey;
  private final String clientId;
  private final String clientSecret;
  private final Map<String, Token> tokens = new ConcurrentHashMap<>();
  private final List<Payment> payments = new ArrayList<>();
  private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
  private final AtomicReference<LocalDate> businessDate;
  private int tokensIssued;

  OperaSimulator(Clock clock, @Value("${sim.opera.token-ttl-seconds:3600}") long tokenTtlSeconds, @Value("${sim.opera.app-key:demo-app-key}") String appKey,
                 @Value("${sim.opera.client-id:fpbridge}") String clientId, @Value("${sim.opera.client-secret:fpbridge-secret}") String clientSecret) {
    this.clock = clock;
    this.tokenTtlSeconds = tokenTtlSeconds;
    this.appKey = appKey;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.businessDate = new AtomicReference<>(LocalDate.now(clock));
  }

  public synchronized Optional<Token> issueToken(String appKeyHeader, String id, String secret) {
    if (!appKey.equals(appKeyHeader) || !clientId.equals(id) || !clientSecret.equals(secret)) {
      return Optional.empty();
    }
    Token token = new Token("sim-" + UUID.randomUUID(), Instant.now(clock).plusSeconds(tokenTtlSeconds));
    tokens.put(token.value(), token);
    tokensIssued++;
    return Optional.of(token);
  }

  /** Every issued token stops working at once, as if its lifetime had ended. */
  public void expireTokens() { tokens.clear(); }

  public boolean appKeyMatches(String header) { return appKey.equals(header); }

  public long tokenTtlSeconds() { return tokenTtlSeconds; }

  public boolean tokenValid(String bearer) {
    Token token = bearer == null ? null : tokens.get(bearer);
    return token != null && token.expiresAt().isAfter(Instant.now(clock));
  }

  public Fault fault() { return fault.get(); }
  public void arm(Fault f) { fault.set(f); }
  public boolean consume(Fault f) { return fault.compareAndSet(f, Fault.NONE); }
  public LocalDate businessDate() { return businessDate.get(); }

  /** Night audit: the business date moves forward one day. */
  public LocalDate nightAudit() { return businessDate.updateAndGet(d -> d.plusDays(1)); }

  public boolean reservationExists(String reservationId) { return !reservationId.startsWith("GHOST"); }
  public boolean reservationClosed(String reservationId) { return reservationId.startsWith("CLOSED") || "F-CLOSED".equals(reservationId); }

  public synchronized Payment post(String hotelId, String reservationId, int folioWindow, String paymentCode, String cashierId, String amount,
                                   String currency, LocalDate date, String reference, String pspRef) {
    Payment payment = new Payment("TX-" + (payments.size() + 1000), hotelId, reservationId, folioWindow, paymentCode, cashierId, amount, currency, date, reference, pspRef, Instant.now(clock));
    payments.add(payment);
    return payment;
  }

  public synchronized List<Payment> find(String hotelId, String reference) {
    return payments.stream().filter(p -> p.hotelId().equals(hotelId) && p.reference().equals(reference)).toList();
  }

  public synchronized Map<String, Object> state() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("businessDate", businessDate.get().toString());
    state.put("fault", fault.get().name());
    state.put("tokensIssued", tokensIssued);
    state.put("payments", List.copyOf(payments));
    return state;
  }

  public synchronized void reset() {
    payments.clear();
    tokens.clear();
    tokensIssued = 0;
    fault.set(Fault.NONE);
    businessDate.set(LocalDate.now(clock));
  }
}
