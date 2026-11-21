package dev.foliopay.domain.model;

import dev.foliopay.domain.rule.SafeText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One attempt to take money from a tokenised card. An idempotency key is unique per client (D-40), so a repeat can never make a second
 * charge, and two integrators that both use {@code order-1} get two charges, neither able to read the other's.
 */
@Entity
@Table(name = "charge", uniqueConstraints = @UniqueConstraint(name = "uk_charge_client_key", columnNames = {"client_id", "idempotency_key"}))
public class Charge {
  public enum Status { PENDING, CAPTURED, DECLINED }

  /** The namespace of the bridge's own keys (payment links, shadow runs): one namespace, whichever client relayed the payment. */
  public static final String BRIDGE_CLIENT = "";

  @Id private String id = UUID.randomUUID().toString();

  /** Optimistic lock: a request holding a stale copy cannot overwrite a newer state. */
  @Version private Long version;

  /** Whose idempotency key this is: the authenticated client, or {@link #BRIDGE_CLIENT} for the bridge's own keys. */
  @Column(name = "client_id", nullable = false, length = 128)
  private String clientId;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(name = "booking_ref", nullable = false)
  private String bookingRef;

  @Column(name = "folio_id", nullable = false)
  private String folioId;

  @Column(name = "business_date", nullable = false)
  private LocalDate businessDate;

  @Column(name = "card_token", nullable = false)
  private String cardToken;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.PENDING;

  @Column(name = "psp_ref")
  private String pspRef;

  @Column(name = "last_error")
  private String lastError;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  /** When the row last changed: the webhook relay looks for recent changes, not recent creations (D-35). */
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = createdAt;

  protected Charge() {}

  public Charge(String clientId, String idempotencyKey, String bookingRef, String folioId, LocalDate businessDate, String cardToken, Money amount) {
    this.clientId = clientId;
    this.idempotencyKey = idempotencyKey;
    this.bookingRef = bookingRef;
    this.folioId = folioId;
    this.businessDate = businessDate;
    this.cardToken = cardToken;
    this.amountMinor = amount.minor();
    this.currency = amount.currency();
  }

  @PrePersist
  @PreUpdate
  void touch() {
    updatedAt = Instant.now();
  }

  /**
   * True when a repeat of the request carries exactly the parameters this charge was created with: every field that decides where the money
   * goes. A repeat that names no business date means "the date this charge was taken", so a charge left pending is still resumable after
   * night audit; a repeat that names a different date is a different request.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public boolean matches(String bookingRef, String folioId, LocalDate businessDate, String token, Money amount) {
    return this.bookingRef.equals(bookingRef) && this.folioId.equals(folioId) && (businessDate == null || this.businessDate.equals(businessDate))
        && cardToken.equals(token) && amountMinor == amount.minor() && currency.equals(amount.currency());
  }

  /**
   * The idempotency key the processor is given for one step ({@code auth}, {@code cap}, {@code void}): this charge's own id, so it is the
   * same on every retry and can never collide with another client's charge (D-3).
   */
  public String processorKey(String step) {
    return id + ":" + step;
  }

  /** CAPTURED beats DECLINED beats PENDING: when two requests race, the one that knows more wins and nothing already learned is lost. */
  public boolean isBehind(Charge other) {
    return other.rank() > rank();
  }

  public void adopt(Charge other) {
    status = other.status;
    pspRef = other.pspRef;
    lastError = other.lastError;
    attempts = Math.max(attempts, other.attempts);
  }

  private int rank() {
    return switch (status) {
      case PENDING -> 0;
      case DECLINED -> 1;
      case CAPTURED -> 2;
    };
  }

  public void markCaptured(String ref) {
    status = Status.CAPTURED;
    pspRef = ref;
    lastError = null;
  }

  public void markDeclined(String reason) {
    status = Status.DECLINED;
    lastError = SafeText.clean(reason);
  }

  public void recordFailedAttempt(String reason) {
    attempts++;
    lastError = SafeText.clean(reason);
  }

  public Money amount() { return new Money(amountMinor, currency); }
  public String getId() { return id; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public String getBookingRef() { return bookingRef; }
  public String getFolioId() { return folioId; }
  public LocalDate getBusinessDate() { return businessDate; }
  public String getCardToken() { return cardToken; }
  public long getAmountMinor() { return amountMinor; }
  public String getCurrency() { return currency; }
  public Status getStatus() { return status; }
  public String getPspRef() { return pspRef; }
  public String getLastError() { return lastError; }
  public int getAttempts() { return attempts; }
  public Instant getCreatedAt() { return createdAt; }
}
