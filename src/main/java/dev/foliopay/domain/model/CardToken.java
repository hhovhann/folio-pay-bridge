package dev.foliopay.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * What is stored for a card: ciphertext, the wrapped data key, and the digits PCI allows (first six and last four, for display). No
 * plaintext PAN, no CVV. At the end of its retention period (D-44) the ciphertext and the wrapped key are destroyed and the row stays, so
 * the charges that name the card stay valid.
 */
@Entity
@Table(name = "card_token")
public class CardToken {
  @Id private String token;

  /**
   * Never changes after tokenisation; it is here so Spring Data sees a new card as new (a null version) and inserts it directly, instead
   * of reading the row first to decide between insert and update, as it must for an entity whose id is assigned in code.
   */
  @Version private Long version;

  @Column(name = "sealed_pan", nullable = false, length = 256)
  private byte[] sealedPan;

  @Column(name = "wrapped_key", nullable = false, length = 1024)
  private byte[] wrappedKey;

  @Column(name = "key_id", nullable = false)
  private String keyId;

  @Column(nullable = false, length = 6)
  private String bin6;

  @Column(nullable = false, length = 4)
  private String last4;

  @Column(nullable = false, length = 5)
  private String expiry;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_used_at", nullable = false)
  private Instant lastUsedAt;

  @Column(name = "purged_at")
  private Instant purgedAt;

  protected CardToken() {}

  public CardToken(String token, byte[] sealedPan, byte[] wrappedKey, String keyId, Pan pan, String expiry, Instant storedAt) {
    this.token = token;
    this.createdAt = storedAt;
    this.lastUsedAt = storedAt;
    this.sealedPan = sealedPan;
    this.wrappedKey = wrappedKey;
    this.keyId = keyId;
    this.bin6 = pan.bin6();
    this.last4 = pan.last4();
    this.expiry = expiry;
  }

  public String getToken() { return token; }
  public byte[] getSealedPan() { return sealedPan; }
  public byte[] getWrappedKey() { return wrappedKey; }
  public String getKeyId() { return keyId; }
  public String getBin6() { return bin6; }
  public String getLast4() { return last4; }
  public String getExpiry() { return expiry; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getLastUsedAt() { return lastUsedAt; }
}
