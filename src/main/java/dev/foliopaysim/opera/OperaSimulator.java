package dev.foliopaysim.opera;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * An OPERA-style property management system, in memory: reservations with folio windows, room charges posted at night audit, payments, a
 * balance, check-in and check-out. It behaves the way a real one does where it matters to a payment bridge: access tokens expire, the
 * business date moves only at night audit and every posting lands on it, a checked-out reservation refuses payments, an unknown cashier or
 * payment method is refused, it does NOT deduplicate (so a caller that is unsure must look first), and it can be made to fail in the ways a
 * real one fails. Amounts are minor units inside; OHIP's decimals exist only on the wire.
 */
@Component
@Profile("opera")
public class OperaSimulator {
  /** LOCK_CONFLICT_NEXT: another workstation holds the reservation. Real OPERA has such locks; the status OHIP gives them is not in Oracle's
   * spec, so the simulator's 409 is an assumption to confirm in the sandbox (the adapter retries any 409). */
  public enum Fault { NONE, DOWN, THROTTLE_NEXT, LOCK_CONFLICT_NEXT, TIMEOUT_AFTER_POST }

  public enum Status { DUE_IN, IN_HOUSE, CHECKED_OUT }

  /** The payment method codes this hotel accepts (OPERA's list of values), and its cashiers: 9001 is the bridge's interface cashier. */
  static final Set<String> PAYMENT_METHODS = Set.of("CC", "VA", "MC", "AX", "CA");
  static final Map<Long, String> CASHIERS = Map.of(9001L, "FPBRIDGE", 100L, "FRONT DESK", 101L, "NIGHT AUDIT");
  private static final String ROOM_CODE = "1000";

  /** One line on a folio. {@code type} is OHIP's transaction type: Revenue (a charge) or Payment. */
  public record Posting(long transactionNo, String hotelId, String reservationId, int folioWindowNo, String type, String code, String description,
                        long amountMinor, String currency, LocalDate businessDate, String reference, String remark, long cashierId, Instant postedAt) {
    public String amount() { return decimal(amountMinor, currency); }
    public String cashierName() { return CASHIERS.getOrDefault(cashierId, "?"); }
  }

  public record Reservation(String id, String guest, Status status, String room, LocalDate arrival, int nights, long rateMinor, String currency) {
    Reservation with(Status newStatus, String newRoom) { return new Reservation(id, guest, newStatus, newRoom, arrival, nights, rateMinor, currency); }
  }

  public record Token(String value, Instant expiresAt) {}

  private final Clock clock;
  private final long tokenTtlSeconds;
  private final String appKey;
  private final String clientId;
  private final String clientSecret;
  private final String enterpriseId;
  private final Map<String, Token> tokens = new ConcurrentHashMap<>();
  private final Map<String, Reservation> reservations = new LinkedHashMap<>();
  private final List<Posting> postings = new ArrayList<>();
  private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
  private final AtomicReference<LocalDate> businessDate;
  private int tokensIssued;
  /** Postings per window on one page of getFolio. The simulator's own choice: OPERA's default page size is a sandbox question. */
  private int folioPageSize = DEFAULT_FOLIO_PAGE_SIZE;
  private static final int DEFAULT_FOLIO_PAGE_SIZE = 20;

  OperaSimulator(Clock clock, @Value("${sim.opera.token-ttl-seconds:3600}") long tokenTtlSeconds, @Value("${sim.opera.app-key:demo-app-key}") String appKey,
                 @Value("${sim.opera.client-id:fpbridge}") String clientId, @Value("${sim.opera.client-secret:fpbridge-secret}") String clientSecret,
                 @Value("${sim.opera.enterprise-id:DEMOENT}") String enterpriseId) {
    this.clock = clock;
    this.tokenTtlSeconds = tokenTtlSeconds;
    this.appKey = appKey;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.enterpriseId = enterpriseId;
    this.businessDate = new AtomicReference<>(LocalDate.now(clock));
    seed();
  }

  // ---- OAuth (client credentials) and the gateway's own checks
  public synchronized Optional<Token> issueToken(String appKeyHeader, String enterpriseHeader, String id, String secret) {
    if (!appKey.equals(appKeyHeader) || !enterpriseId.equals(enterpriseHeader) || !clientId.equals(id) || !clientSecret.equals(secret)) {
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

  // ---- reservations and folios
  /**
   * The reservation, or empty if it does not exist. A reservation the simulator has not seen is created on first use (due in), so a test or
   * a booking can name any reservation; one whose id starts with GHOST never exists, and one starting with CLOSED (or F-CLOSED) is checked out.
   */
  public synchronized Optional<Reservation> reservation(String id) {
    if (id.startsWith("GHOST")) {
      return Optional.empty();
    }
    return Optional.of(reservations.computeIfAbsent(id, r -> new Reservation(r, "Guest " + r,
        r.startsWith("CLOSED") || "F-CLOSED".equals(r) ? Status.CHECKED_OUT : Status.DUE_IN, "", businessDate.get(), 1, 0, "USD")));
  }

  /** A reservation the PMS itself created (a booking at the front desk or from a channel). */
  public synchronized Reservation book(String id, String guest, LocalDate arrival, int nights, long totalMinor, String currency) {
    Reservation reservation = new Reservation(id, guest, Status.DUE_IN, "", arrival, nights, totalMinor / Math.max(1, nights), currency);
    reservations.put(id, reservation);
    return reservation;
  }

  public synchronized Posting postPayment(String hotelId, String reservationId, int window, String method, long cashierId, long amountMinor, String currency,
                                          String reference, String remark) {
    return post(hotelId, reservationId, window, "Payment", method, "Payment " + method, amountMinor, currency, reference, remark, cashierId);
  }

  /** One room night on window 1, by the front desk or by night audit. */
  public synchronized Optional<Posting> postRoomCharge(String reservationId, long cashierId) {
    return reservation(reservationId).filter(r -> r.status() == Status.IN_HOUSE && r.rateMinor() > 0)
        .map(r -> post("DEMO1", r.id(), 1, "Revenue", ROOM_CODE, "Room charge", r.rateMinor(), r.currency(), "", "", cashierId));
  }

  private Posting post(String hotelId, String reservationId, int window, String type, String code, String description, long amountMinor, String currency,
                       String reference, String remark, long cashierId) {
    Posting posting = new Posting(postings.size() + 1000L, hotelId, reservationId, window, type, code, description, amountMinor, currency, businessDate.get(),
        reference, remark, cashierId, Instant.now(clock));
    postings.add(posting);
    return posting;
  }

  public synchronized List<Posting> folio(String reservationId) {
    return postings.stream().filter(p -> p.reservationId().equals(reservationId)).toList();
  }

  public synchronized int folioPageSize() { return folioPageSize; }

  /** A small page lets a test or the front desk see a long folio without posting hundreds of lines. */
  public synchronized void folioPageSize(int size) { folioPageSize = Math.max(1, size); }

  /** Charges minus payments, per currency of the reservation: what the guest still owes (negative when paid in advance). */
  public synchronized long balanceMinor(String reservationId, int window) {
    return postings.stream().filter(p -> p.reservationId().equals(reservationId) && p.folioWindowNo() == window)
        .mapToLong(p -> "Payment".equals(p.type()) ? -p.amountMinor() : p.amountMinor()).sum();
  }

  public synchronized Optional<String> checkIn(String reservationId) {
    Reservation r = reservation(reservationId).orElse(null);
    if (r == null || r.status() != Status.DUE_IN) {
      return Optional.of("Only a due-in reservation can be checked in");
    }
    reservations.put(r.id(), r.with(Status.IN_HOUSE, String.valueOf(301 + reservations.values().stream().filter(x -> x.status() == Status.IN_HOUSE).count())));
    return Optional.empty();
  }

  /** A guest can leave only with every window settled to zero, as at a real front desk. */
  public synchronized Optional<String> checkOut(String reservationId) {
    Reservation r = reservation(reservationId).orElse(null);
    if (r == null || r.status() != Status.IN_HOUSE) {
      return Optional.of("Only an in-house reservation can be checked out");
    }
    boolean settled = folio(reservationId).stream().mapToInt(Posting::folioWindowNo).distinct().allMatch(w -> balanceMinor(reservationId, w) == 0);
    if (!settled) {
      return Optional.of("The folio has a balance: settle it before check-out");
    }
    reservations.put(r.id(), r.with(Status.CHECKED_OUT, r.room()));
    return Optional.empty();
  }

  /** Night audit: room and tax post to every in-house guest on the day being closed, then the business date moves forward one day. */
  public synchronized LocalDate nightAudit() {
    List.copyOf(reservations.values()).forEach(r -> postRoomCharge(r.id(), 101L));
    return businessDate.updateAndGet(d -> d.plusDays(1));
  }

  public synchronized Map<String, Object> state() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("hotelId", "DEMO1");
    state.put("businessDate", businessDate.get().toString());
    state.put("fault", fault.get().name());
    state.put("tokensIssued", tokensIssued);
    state.put("reservations", reservations.values().stream().map(r -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", r.id());
      row.put("guest", r.guest());
      row.put("status", r.status().name());
      row.put("room", r.room());
      row.put("arrival", r.arrival().toString());
      row.put("balance", decimal(balanceMinor(r.id(), 1), r.currency()));
      row.put("currency", r.currency());
      return row;
    }).toList());
    state.put("payments", postings.stream().filter(p -> "Payment".equals(p.type())).map(OperaSimulator::row).toList());
    return state;
  }

  static Map<String, Object> row(Posting p) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("transactionNo", p.transactionNo());
    row.put("hotelId", p.hotelId());
    row.put("reservationId", p.reservationId());
    row.put("folioWindow", p.folioWindowNo());
    row.put("type", p.type());
    row.put("code", p.code());
    row.put("description", p.description());
    row.put("cashierId", p.cashierId());
    row.put("cashierName", p.cashierName());
    row.put("amount", p.amount());
    row.put("currency", p.currency());
    row.put("businessDate", p.businessDate().toString());
    row.put("reference", p.reference());
    row.put("remark", p.remark());
    return row;
  }

  public synchronized void reset() {
    postings.clear();
    reservations.clear();
    tokens.clear();
    tokensIssued = 0;
    folioPageSize = DEFAULT_FOLIO_PAGE_SIZE;
    fault.set(Fault.NONE);
    businessDate.set(LocalDate.now(clock));
    seed();
  }

  /** Two synthetic guests already in house, so the front desk has folios with room charges to show before any booking arrives. */
  private void seed() {
    book("RES-1001", "Synthetic, Alex", businessDate.get().minusDays(1), 3, 75_000, "USD");
    checkIn("RES-1001");
    postRoomCharge("RES-1001", 101L);
    book("RES-1002", "Synthetic, Sam", businessDate.get(), 2, 64_000, "USD");
  }

  // ---- decimals on the wire, minor units inside (no floating point)
  public static int digits(String currency) {
    try {
      return Currency.getInstance(currency).getDefaultFractionDigits();
    } catch (IllegalArgumentException | NullPointerException e) {
      return -1;
    }
  }

  public static String decimal(long minor, String currency) {
    int digits = Math.max(0, digits(currency));
    String sign = minor < 0 ? "-" : "";
    String abs = Long.toString(Math.abs(minor));
    if (digits == 0) {
      return sign + abs;
    }
    String padded = "0".repeat(Math.max(0, digits + 1 - abs.length())) + abs;
    return sign + padded.substring(0, padded.length() - digits) + "." + padded.substring(padded.length() - digits);
  }

  /** A positive decimal with no more places than the currency has, as minor units; empty when it is not one. */
  public static Optional<Long> minor(String decimal, String currency) {
    int digits = digits(currency);
    if (digits < 0 || decimal == null || !decimal.matches("\\d{1,12}(\\.\\d+)?")) {
      return Optional.empty();
    }
    String[] parts = decimal.split("\\.");
    String fraction = parts.length > 1 ? parts[1] : "";
    if (fraction.length() > digits) {
      return Optional.empty();
    }
    long value = Long.parseLong(parts[0] + fraction + "0".repeat(digits - fraction.length()));
    return value > 0 ? Optional.of(value) : Optional.empty();
  }
}
