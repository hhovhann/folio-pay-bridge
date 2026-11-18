package dev.foliopay.repository;

import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Notification;
import dev.foliopay.domain.model.Posting;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRepository extends JpaRepository<Notification, String> {
  long countByStatus(Notification.Status status);

  /**
   * Charges in {@code status} that changed since {@code since}, with no event of {@code type} yet; shadow runs are not revenue and are never
   * announced. Oldest change first. The window keeps each run to recent rows instead of rescanning all history every few seconds, and it is
   * on the last change, not the creation, so a charge resumed weeks later is still announced.
   */
  @Query("select c from Charge c where c.status = :status and c.updatedAt >= :since and c.idempotencyKey not like 'shadow-%' "
      + "and not exists (select n.id from Notification n where n.eventId = concat(:prefix, c.id)) order by c.updatedAt")
  List<Charge> chargesToAnnounce(@Param("status") Charge.Status status, @Param("prefix") String eventIdPrefix, @Param("since") Instant since, Pageable page);

  /** Postings in {@code status} that changed since {@code since}, with no event of {@code type} yet, leaving out shadow runs. Oldest first. */
  @Query("select p from Posting p where p.status = :status and p.updatedAt >= :since "
      + "and not exists (select c.id from Charge c where c.id = p.chargeId and c.idempotencyKey like 'shadow-%') "
      + "and not exists (select n.id from Notification n where n.eventId = concat(:prefix, p.id)) order by p.updatedAt")
  List<Posting> postingsToAnnounce(@Param("status") Posting.Status status, @Param("prefix") String eventIdPrefix, @Param("since") Instant since, Pageable page);

  @Query("select n from Notification n where n.status = dev.foliopay.domain.model.Notification.Status.PENDING "
      + "and n.nextAttemptAt <= :now and (n.leaseUntil is null or n.leaseUntil < :now) order by n.nextAttemptAt")
  List<Notification> due(@Param("now") Instant now, Pageable page);

  /** Atomically takes one delivery until {@code until}, so two instances never send the same event at the same moment. 1 if taken. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Notification n set n.leaseUntil = :until, n.version = n.version + 1 where n.id = :id "
      + "and n.status = dev.foliopay.domain.model.Notification.Status.PENDING and (n.leaseUntil is null or n.leaseUntil < :now)")
  int claim(@Param("id") String id, @Param("now") Instant now, @Param("until") Instant until);
}
