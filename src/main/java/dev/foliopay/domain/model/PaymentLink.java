package dev.foliopay.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A request for the guest to pay a booking. OPEN means nobody is paying; PAYING means one card holds the claim (set by one atomic update, so
 * two cards cannot both charge); PAID is final. {@code openSlot} holds the booking reference while the link is OPEN or PAYING and is null
 * otherwise, and its unique constraint allows at most one live link per booking.
 */
@Entity
@Table(name = "payment_link", uniqueConstraints = @UniqueConstraint(columnNames = "open_slot"))
public class PaymentLink {
  public enum Status { OPEN, PAYING, PAID, CANCELLED, SUPERSEDED }

  @Id private String id = "lnk_" + UUID.randomUUID().toString().replace("-", "");

  /** Optimistic lock: a request holding a stale copy cannot overwrite a newer state. */
  @Version private Long version;

  @Column(name = "booking_ref", nullable = false)
  private String bookingRef;

  @Column(name = "open_slot")
  private String openSlot;

  @Column(name = "folio_id", nullable = false)
  private String folioId;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  @Column(nullable = false)
  private String reason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.OPEN;

  /** Which card holds the claim: a short hash of the card token, never the token itself. */
  @Column(name = "payer_fingerprint")
  private String payerFingerprint;

  protected PaymentLink() {}

  public PaymentLink(String bookingRef, String folioId, Money amount, LocalDate dueDate, String reason) {
    this.bookingRef = bookingRef;
    this.openSlot = bookingRef;
    this.folioId = folioId;
    this.amountMinor = amount.minor();
    this.currency = amount.currency();
    this.dueDate = dueDate;
    this.reason = reason;
  }

  public boolean isOpen() { return status == Status.OPEN; }
  public boolean isPaying() { return status == Status.PAYING; }
  public boolean isPaid() { return status == Status.PAID; }
  public boolean isHeldBy(String fingerprint) { return fingerprint.equals(payerFingerprint); }
  public boolean hasTerms(String folioId, Money amount, LocalDate dueDate) {
    return this.folioId.equals(folioId) && amountMinor == amount.minor() && currency.equals(amount.currency()) && this.dueDate.equals(dueDate);
  }

  public void cancel() { close(Status.CANCELLED); }
  public void supersede() { close(Status.SUPERSEDED); }

  private void close(Status closed) {
    status = closed;
    openSlot = null;
  }

  public Money amount() { return new Money(amountMinor, currency); }
  public String getId() { return id; }
  public String getBookingRef() { return bookingRef; }
  public String getFolioId() { return folioId; }
  public long getAmountMinor() { return amountMinor; }
  public String getCurrency() { return currency; }
  public LocalDate getDueDate() { return dueDate; }
  public String getReason() { return reason; }
  public Status getStatus() { return status; }
}
