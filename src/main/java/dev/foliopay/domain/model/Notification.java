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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One event the integrator is told about by webhook (D-35). The event id is deterministic ({@code payment.captured:<chargeId>}) and
 * unique, so the same event is never queued twice and a receiver can deduplicate a repeat delivery. It ends DELIVERED, or FAILED after the
 * last attempt with the reason: never dropped. The payload is built once, from stored state, and never holds card data or a card token.
 */
@Entity
@Table(name = "notification", uniqueConstraints = @UniqueConstraint(columnNames = "event_id"))
public class Notification {
  public enum Status { PENDING, DELIVERED, FAILED }

  /** What happened, named as the receiver sees it. */
  public enum Type {
    PAYMENT_CAPTURED("payment.captured"),
    PAYMENT_DECLINED("payment.declined"),
    FOLIO_POSTED("folio.posted"),
    FOLIO_DEAD_LETTERED("folio.dead_lettered");

    private final String wireName;

    Type(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    public String eventId(String subjectId) { return wireName + ":" + subjectId; }
  }

  private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
  private static final Duration LONGEST_WAIT = Duration.ofHours(1);

  @Id private String id = UUID.randomUUID().toString();

  @Version private Long version;

  @Column(name = "event_id", nullable = false, length = 120)
  private String eventId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private Type type;

  @Column(name = "subject_id", nullable = false, length = 64)
  private String subjectId;

  @Column(nullable = false, length = 4000)
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status = Status.PENDING;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "last_error")
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  protected Notification() {}

  public Notification(Type type, String subjectId, String payload, Instant now) {
    this.type = type;
    this.subjectId = subjectId;
    this.eventId = type.eventId(subjectId);
    this.payload = payload;
    this.createdAt = now;
    this.nextAttemptAt = now;
  }

  public void delivered(Instant now) {
    status = Status.DELIVERED;
    deliveredAt = now;
    lastError = null;
    leaseUntil = null;
    attempts++;
  }

  /** Waits twice as long after each failure (30 s, 1 min, 2 min ... at most an hour); after the last attempt it is FAILED, for a person. */
  public void failed(String reason, Instant now, int maxAttempts) {
    attempts++;
    lastError = SafeText.clean(reason);
    leaseUntil = null;
    if (attempts >= maxAttempts) {
      status = Status.FAILED;
      return;
    }
    Duration wait = FIRST_RETRY.multipliedBy(1L << Math.min(attempts - 1, 20));
    nextAttemptAt = now.plus(wait.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : wait);
  }

  /** An operator asks for a FAILED event to be sent again (the receiver was fixed): it starts a fresh round of attempts. */
  public void redeliver(Instant now) {
    if (status == Status.FAILED) {
      status = Status.PENDING;
      attempts = 0;
      nextAttemptAt = now;
    }
  }

  public String getId() { return id; }
  public String getEventId() { return eventId; }
  public Type getType() { return type; }
  public String getSubjectId() { return subjectId; }
  public String getPayload() { return payload; }
  public Status getStatus() { return status; }
  public int getAttempts() { return attempts; }
  public Instant getNextAttemptAt() { return nextAttemptAt; }
  public String getLastError() { return lastError; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getDeliveredAt() { return deliveredAt; }
}
