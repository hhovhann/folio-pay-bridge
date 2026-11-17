package dev.foliopay.domain.model;

import dev.foliopay.domain.rule.SafeText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** One attempt to take money from a tokenised card. The idempotency key is unique, so a repeat can never make a second charge. */
@Entity
@Table(name = "charge", uniqueConstraints = @UniqueConstraint(columnNames = "idempotency_key"))
public class Charge {
  public enum Status { PENDING, CAPTURED, DECLINED }

  @Id private String id = UUID.randomUUID().toString();

  /** Optimistic lock: a request holding a stale copy cannot overwrite a newer state. */
  @Version private Long version;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(name = "booking_ref", nullable = false)
  private String bookingRef;

  @Column(name = "folio_id", nullable = false)
  private String folioId;

  @Column(name = "business_date", nullable = false)
  private java.time.LocalDate businessDate;

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

  protected Charge() {}

  public Charge(String idempotencyKey, String bookingRef, String folioId, java.time.LocalDate businessDate, String cardToken, Money amount) {
    this.idempotencyKey = idempotencyKey;
    this.bookingRef = bookingRef;
    this.folioId = folioId;
    this.businessDate = businessDate;
    this.cardToken = cardToken;
    this.amountMinor = amount.minor();
    this.currency = amount.currency();
  }

  /** True when a repeat of the request carries exactly the parameters this charge was created with: every field that decides where the money goes. */
  public boolean matches(String bookingRef, String folioId, java.time.LocalDate businessDate, String token, Money amount) {
    return this.bookingRef.equals(bookingRef) && this.folioId.equals(folioId) && this.businessDate.equals(businessDate) && cardToken.equals(token)
        && amountMinor == amount.minor() && currency.equals(amount.currency());
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

  public void captured(String ref) {
    status = Status.CAPTURED;
    pspRef = ref;
    lastError = null;
  }

  public void declined(String reason) {
    status = Status.DECLINED;
    lastError = SafeText.clean(reason);
  }

  public void failedAttempt(String reason) {
    attempts++;
    lastError = SafeText.clean(reason);
  }

  public String getFolioId() { return folioId; }
  public java.time.LocalDate getBusinessDate() { return businessDate; }
  public Money amount() { return new Money(amountMinor, currency); }
  public String getId() { return id; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public String getBookingRef() { return bookingRef; }
  public String getCardToken() { return cardToken; }
  public long getAmountMinor() { return amountMinor; }
  public String getCurrency() { return currency; }
  public Status getStatus() { return status; }
  public String getPspRef() { return pspRef; }
  public String getLastError() { return lastError; }
  public int getAttempts() { return attempts; }
  public Instant getCreatedAt() { return createdAt; }
}
