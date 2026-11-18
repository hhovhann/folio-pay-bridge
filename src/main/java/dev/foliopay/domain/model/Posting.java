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

/** A captured payment on its way to the guest folio. It ends POSTED, or DEAD_LETTER with a category and an error: never dropped. */
@Entity
@Table(name = "posting", uniqueConstraints = @UniqueConstraint(name = "uk_posting_idempotency_key", columnNames = "idempotency_key"))
public class Posting {
  public enum Status { PENDING, POSTED, DEAD_LETTER }

  /** Why a posting was dead-lettered, so an operator knows whether replay can help. */
  public enum FailureCategory { OPERA_UNAVAILABLE, REJECTED_BY_OPERA }

  @Id private String id = UUID.randomUUID().toString();

  /** Optimistic lock: a request holding a stale copy cannot overwrite a newer state. */
  @Version private Long version;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(name = "charge_id", nullable = false)
  private String chargeId;

  @Column(name = "folio_id", nullable = false)
  private String folioId;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "business_date", nullable = false)
  private LocalDate businessDate;

  @Column(name = "psp_ref")
  private String pspRef;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.PENDING;

  @Column(name = "opera_ref")
  private String operaRef;

  @Enumerated(EnumType.STRING)
  @Column(name = "failure_category")
  private FailureCategory failureCategory;

  @Column(name = "last_error")
  private String lastError;

  @Column(nullable = false)
  private int attempts;

  /** While set and in the future, one request is talking to OPERA about this posting and no other may (OPERA does not deduplicate). */
  @Column(name = "lease_until")
  private Instant leaseUntil;

  /** Which request holds the lease, so a request whose lease ran out and was taken over can tell before it posts. */
  @Column(name = "lease_owner", length = 36)
  private String leaseOwner;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  /** When the row last changed: a posting replayed weeks after it was created is still announced (D-35). Leases do not count. */
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = createdAt;

  protected Posting() {}

  public Posting(String idempotencyKey, String chargeId, String folioId, Money amount, LocalDate businessDate, String pspRef) {
    this.pspRef = pspRef;
    this.idempotencyKey = idempotencyKey;
    this.chargeId = chargeId;
    this.folioId = folioId;
    this.amountMinor = amount.minor();
    this.currency = amount.currency();
    this.businessDate = businessDate;
  }

  @PrePersist
  @PreUpdate
  void touch() {
    updatedAt = Instant.now();
  }

  public void markPosted(String ref) {
    status = Status.POSTED;
    operaRef = ref;
    failureCategory = null;
    lastError = null;
    releaseLease();
  }

  public void moveToDeadLetter(FailureCategory category, String reason) {
    status = Status.DEAD_LETTER;
    failureCategory = category;
    lastError = SafeText.clean(reason);
    releaseLease();
  }

  private void releaseLease() {
    leaseUntil = null;
    leaseOwner = null;
  }

  /** POSTED beats DEAD_LETTER beats PENDING: a request that lost a race never overwrites a post that already happened. */
  public boolean isBehind(Posting other) {
    return other.rank() > rank();
  }

  public void adopt(Posting other) {
    status = other.status;
    operaRef = other.operaRef;
    failureCategory = other.failureCategory;
    lastError = other.lastError;
    attempts = Math.max(attempts, other.attempts);
    leaseUntil = other.leaseUntil;
    leaseOwner = other.leaseOwner;
  }

  private int rank() {
    return switch (status) {
      case PENDING -> 0;
      case DEAD_LETTER -> 1;
      case POSTED -> 2;
    };
  }

  public void recordAttempt() { attempts++; }

  public boolean isPosted() { return status == Status.POSTED; }

  /** OPERA refused it: retrying the same post cannot help, so it waits for a person to fix the folio. */
  public boolean isRejected() { return failureCategory == FailureCategory.REJECTED_BY_OPERA; }

  public String getPspRef() { return pspRef; }
  public Money amount() { return new Money(amountMinor, currency); }
  public String getId() { return id; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public String getChargeId() { return chargeId; }
  public String getFolioId() { return folioId; }
  public long getAmountMinor() { return amountMinor; }
  public String getCurrency() { return currency; }
  public LocalDate getBusinessDate() { return businessDate; }
  public Status getStatus() { return status; }
  public String getOperaRef() { return operaRef; }
  public FailureCategory getFailureCategory() { return failureCategory; }
  public String getLastError() { return lastError; }
  public int getAttempts() { return attempts; }
  public Instant getLeaseUntil() { return leaseUntil; }
  public String getLeaseOwner() { return leaseOwner; }
  public Instant getCreatedAt() { return createdAt; }
}
