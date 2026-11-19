package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.foliopay.Fixtures;
import dev.foliopay.config.WebhookProperties;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Notification;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.gateway.WebhookSender;
import dev.foliopay.repository.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** The relay's decisions with test doubles; WebhookIntegrationTest proves the queries and the HTTP side against the real things. */
@DisplayName("Webhook relay decisions, with test doubles")
class NotificationServiceImplTest {
  private static final Instant NOW = Instant.parse("2026-11-18T08:00:00Z");
  private final NotificationRepository repository = mock(NotificationRepository.class);
  private final WebhookSender sender = mock(WebhookSender.class);
  private NotificationServiceImpl service;

  @BeforeEach
  void setUp() {
    service = service(12);
    when(repository.chargesToAnnounce(any(), anyString(), any(), any())).thenReturn(List.of());
    when(repository.postingsToAnnounce(any(), anyString(), any(), any())).thenReturn(List.of());
    when(repository.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));
    when(repository.claim(anyString(), any(), any())).thenReturn(1);
  }

  private NotificationServiceImpl service(int maxAttempts) {
    WebhookProperties settings = new WebhookProperties("https://hooks.example.test/x", "s".repeat(32), maxAttempts, Duration.ofSeconds(5), false, Duration.ofDays(30));
    return new NotificationServiceImpl(repository, sender, settings, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Notification due() {
    Notification n = new Notification(Notification.Type.PAYMENT_CAPTURED, "ch-1", "{\"id\":\"payment.captured:ch-1\"}", NOW);
    when(repository.due(any(), any(Pageable.class))).thenReturn(List.of(n));
    when(repository.findById(n.getId())).thenReturn(Optional.of(n));
    return n;
  }

  @Test
  @DisplayName("A dead-lettered posting is announced with its category and reason, so the hotel knows why it is not on the folio")
  void aDeadLetteredPostingIsAnnouncedWithItsCategoryAndReasonSoTheHotelKnowsWhyItIsNotOnTheFolio() {
    Posting p = Fixtures.posting("post:ch-1", "ch-1", "RES-1", new Money(5_000, "USD"), Fixtures.DAY);
    p.moveToDeadLetter(Posting.FailureCategory.REJECTED_BY_OPERA, "FOLIO_CLOSED");
    when(repository.postingsToAnnounce(eq(Posting.Status.DEAD_LETTER), eq("folio.dead_lettered:"), any(), any())).thenReturn(List.of(p));

    assertThat(service.collect()).isEqualTo(1);

    ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
    verify(repository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getEventId()).isEqualTo("folio.dead_lettered:" + p.getId());
    assertThat(saved.getValue().getPayload()).contains("\"failureCategory\":\"REJECTED_BY_OPERA\"", "\"reason\":\"FOLIO_CLOSED\"", "\"type\":\"folio.dead_lettered\"");
  }

  @Test
  @DisplayName("An event another instance queued first is not counted twice")
  void anEventAnotherInstanceQueuedFirstIsNotCountedTwice() {
    Charge c = Fixtures.charge("k-1", "BK-1", "tok_secret", new Money(100, "USD"));
    c.markCaptured("cap_1");
    when(repository.chargesToAnnounce(eq(Charge.Status.CAPTURED), anyString(), any(), any())).thenReturn(List.of(c));
    when(repository.saveAndFlush(any(Notification.class))).thenThrow(new DataIntegrityViolationException("uk_notification_event_id"));

    assertThat(service.collect()).isZero();
  }

  @Test
  @DisplayName("The card token is never in a payload")
  void theCardTokenIsNeverInAPayload() {
    Charge c = Fixtures.charge("k-1", "BK-1", "tok_secret", new Money(100, "USD"));
    c.markCaptured("cap_1");
    when(repository.chargesToAnnounce(eq(Charge.Status.CAPTURED), anyString(), any(), any())).thenReturn(List.of(c));

    service.collect();

    ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
    verify(repository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getPayload()).doesNotContain("tok_secret").contains("\"bookingRef\":\"BK-1\"");
  }

  @Test
  @DisplayName("A receiver that does not answer is retried later, and the last attempt ends failed")
  void aReceiverThatDoesNotAnswerIsRetriedLaterAndTheLastAttemptEndsFailed() {
    Notification n = due();
    when(sender.send(anyString(), anyLong(), anyString(), anyString())).thenThrow(new IllegalStateException("connection refused"));

    assertThat(service(2).deliverDue()).isZero();
    assertThat(n.getStatus()).isEqualTo(Notification.Status.PENDING);
    assertThat(n.getLastError()).isEqualTo("The receiver did not answer: IllegalStateException");

    service(2).deliverDue();
    assertThat(n.getStatus()).isEqualTo(Notification.Status.FAILED);
  }

  @Test
  @DisplayName("An event another instance is sending is not sent again")
  void anEventAnotherInstanceIsSendingIsNotSentAgain() {
    due();
    when(repository.claim(anyString(), any(), any())).thenReturn(0);

    assertThat(service.deliverDue()).isZero();
    verify(sender, never()).send(anyString(), anyLong(), anyString(), anyString());
  }

  @Test
  @DisplayName("A failed event can be redelivered, and an unknown one is not found")
  void aFailedEventCanBeRedeliveredAndAnUnknownOneIsNotFound() {
    Notification n = due();
    n.recordFailure("down", NOW, 1);

    assertThat(service.redeliver(n.getId()).getStatus()).isEqualTo(Notification.Status.PENDING);
    when(repository.findById("nope")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.redeliver("nope")).isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("The event list is bounded and newest first")
  void theListIsBoundedAndNewestFirst() {
    when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
    service.findAll();
    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(repository).findAll(page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(500);
    assertThat(page.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
  }
}
