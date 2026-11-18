package dev.foliopay.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.foliopay.config.WebhookProperties;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Notification;
import dev.foliopay.domain.model.Notification.Type;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.gateway.WebhookSender;
import dev.foliopay.repository.NotificationRepository;
import dev.foliopay.service.NotificationService;
import dev.foliopay.service.support.WebhookSignature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Derives events from the rows that already record what happened, so there is no second write that a crash could lose and no event for
 * a change that was rolled back: if the charge is CAPTURED in the database, a {@code payment.captured} event will be queued, on this
 * instance or another. The unique event id makes queueing idempotent across instances.
 */
@Service
class NotificationServiceImpl implements NotificationService {
  private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);
  private static final int BATCH = 100;
  private static final Duration SEND_LEASE = Duration.ofMinutes(1);
  private final NotificationRepository repository;
  private final WebhookSender sender;
  private final WebhookProperties settings;
  private final ObjectMapper json;
  private final Clock clock;

  NotificationServiceImpl(NotificationRepository repository, WebhookSender sender, WebhookProperties settings, ObjectMapper json, Clock clock) {
    this.repository = repository;
    this.sender = sender;
    this.settings = settings;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public int collect() {
    int queued = 0;
    queued += queueCharges(Charge.Status.CAPTURED, Type.PAYMENT_CAPTURED);
    queued += queueCharges(Charge.Status.DECLINED, Type.PAYMENT_DECLINED);
    queued += queuePostings(Posting.Status.POSTED, Type.FOLIO_POSTED);
    queued += queuePostings(Posting.Status.DEAD_LETTER, Type.FOLIO_DEAD_LETTERED);
    if (queued > 0) {
      log.debug("Queued {} webhook events", queued);
    }
    return queued;
  }

  private int queueCharges(Charge.Status status, Type type) {
    int queued = 0;
    for (Charge c : repository.chargesToAnnounce(status, type.eventId(""), since(), PageRequest.of(0, BATCH))) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("chargeId", c.getId());
      data.put("idempotencyKey", c.getIdempotencyKey());
      data.put("bookingRef", c.getBookingRef());
      data.put("folioId", c.getFolioId());
      data.put("amountMinor", c.getAmountMinor());
      data.put("currency", c.getCurrency());
      data.put("businessDate", c.getBusinessDate().toString());
      data.put("pspRef", c.getPspRef());
      data.put("reason", c.getLastError());
      queued += queue(type, c.getId(), data);
    }
    return queued;
  }

  private int queuePostings(Posting.Status status, Type type) {
    int queued = 0;
    for (Posting p : repository.postingsToAnnounce(status, type.eventId(""), since(), PageRequest.of(0, BATCH))) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("postingId", p.getId());
      data.put("chargeId", p.getChargeId());
      data.put("folioId", p.getFolioId());
      data.put("amountMinor", p.getAmountMinor());
      data.put("currency", p.getCurrency());
      data.put("businessDate", p.getBusinessDate().toString());
      data.put("operaRef", p.getOperaRef());
      data.put("failureCategory", p.getFailureCategory() == null ? null : p.getFailureCategory().name());
      data.put("reason", p.getLastError());
      queued += queue(type, p.getId(), data);
    }
    return queued;
  }

  private Instant since() {
    return clock.instant().minus(settings.lookback());
  }

  /** Only ids, references and amounts: never a card number or a card token (the charge's token is deliberately not read). */
  private int queue(Type type, String subjectId, Map<String, Object> data) {
    Instant now = clock.instant();
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("id", type.eventId(subjectId));
    event.put("type", type.wireName());
    event.put("createdAt", now.toString());
    event.put("data", data);
    try {
      repository.saveAndFlush(new Notification(type, subjectId, json.writeValueAsString(event), now));
      return 1;
    } catch (DataIntegrityViolationException twin) {
      return 0; // another instance queued the same event first
    } catch (JsonProcessingException impossible) { // a map of strings and numbers always serialises
      throw new IllegalStateException(impossible);
    }
  }

  @Override
  public int deliverDue() {
    int delivered = 0;
    Instant now = clock.instant();
    for (Notification due : repository.due(now, PageRequest.of(0, BATCH))) {
      if (repository.claim(due.getId(), now, now.plus(SEND_LEASE)) == 1) {
        delivered += send(repository.findById(due.getId()).orElseThrow()) ? 1 : 0;
      }
    }
    return delivered;
  }

  private boolean send(Notification n) {
    long timestamp = clock.instant().getEpochSecond();
    String signature = WebhookSignature.sign(settings.secret(), timestamp, n.getPayload());
    String failure;
    try {
      int status = sender.send(n.getEventId(), timestamp, n.getPayload(), signature);
      failure = status / 100 == 2 ? null : "The receiver answered " + status;
    } catch (RuntimeException noAnswer) {
      failure = "The receiver did not answer: " + noAnswer.getClass().getSimpleName();
    }
    if (failure == null) {
      n.markDelivered(clock.instant());
      log.debug("Webhook {} delivered on attempt {}", n.getEventId(), n.getAttempts());
    } else {
      n.recordFailure(failure, clock.instant(), settings.maxAttempts());
      if (n.getStatus() == Notification.Status.FAILED) {
        log.error("Webhook {} failed after {} attempts: {}", n.getEventId(), n.getAttempts(), n.getLastError());
      } else {
        log.warn("Webhook {} attempt {} failed, next at {}: {}", n.getEventId(), n.getAttempts(), n.getNextAttemptAt(), n.getLastError());
      }
    }
    repository.save(n);
    return failure == null;
  }

  @Override
  public List<Notification> findAll() {
    return repository.findAll(PageRequest.of(0, 500, Sort.by("createdAt").descending())).getContent();
  }

  @Override
  public Notification redeliver(String id) {
    Notification n = repository.findById(id).orElseThrow(() -> new NotFoundException("Unknown notification"));
    log.info("Redelivery requested for webhook {} ({})", n.getEventId(), n.getStatus());
    n.redeliver(clock.instant());
    return repository.save(n);
  }
}
