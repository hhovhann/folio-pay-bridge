package dev.foliopay.repository;

import dev.foliopay.domain.model.PaymentLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PaymentLinkRepository extends JpaRepository<PaymentLink, String> {
  List<PaymentLink> findByBookingRef(String bookingRef);

  /** Atomically takes the claim on an OPEN link for one card. Returns 1 if this caller got it, 0 if the link was not OPEN. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update PaymentLink l set l.status = dev.foliopay.domain.model.PaymentLink.Status.PAYING, l.payerFingerprint = :fingerprint, l.version = l.version + 1 "
      + "where l.id = :id and l.status = dev.foliopay.domain.model.PaymentLink.Status.OPEN")
  int claim(@Param("id") String id, @Param("fingerprint") String fingerprint);

  /** The claim holder's charge was captured: the link is PAID and no longer counts as the booking's live link. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update PaymentLink l set l.status = dev.foliopay.domain.model.PaymentLink.Status.PAID, l.openSlot = null, l.version = l.version + 1 "
      + "where l.id = :id and l.status = dev.foliopay.domain.model.PaymentLink.Status.PAYING and l.payerFingerprint = :fingerprint")
  int complete(@Param("id") String id, @Param("fingerprint") String fingerprint);

  /** The claim holder's card was declined: the link is OPEN again for any card. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update PaymentLink l set l.status = dev.foliopay.domain.model.PaymentLink.Status.OPEN, l.payerFingerprint = null, l.version = l.version + 1 "
      + "where l.id = :id and l.status = dev.foliopay.domain.model.PaymentLink.Status.PAYING and l.payerFingerprint = :fingerprint")
  int release(@Param("id") String id, @Param("fingerprint") String fingerprint);
}
