package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.foliopay.Fixtures;
import dev.foliopay.config.OperaProperties;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.domain.model.Posting.FailureCategory;
import dev.foliopay.gateway.OperaClient;
import dev.foliopay.gateway.OperaClient.OperaPayment;
import dev.foliopay.gateway.OperaClient.Posted;
import dev.foliopay.gateway.OperaRejectedException;
import dev.foliopay.gateway.OperaUnavailableException;
import dev.foliopay.repository.PostingRepository;
import dev.foliopay.service.support.IdempotentStore;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class PostingServiceImplTest {
  private static final Money AMOUNT = new Money(5_000, "USD");
  private static final LocalDate DAY = Fixtures.DAY;
  private static final OperaProperties SETTINGS = new OperaProperties("HOTEL9", 2, "VCC", 7001);

  private final PostingRepository repository = mock(PostingRepository.class);
  private final OperaClient opera = mock(OperaClient.class);
  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
  private static final Duration LEASE = Duration.ofMinutes(4);
  private final Map<String, Posting> rows = new HashMap<>();
  private PostingServiceImpl service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
    Retry retry = Retry.of("folio", RetryConfig.custom().maxAttempts(3).intervalFunction(IntervalFunction.of(1)).retryExceptions(OperaUnavailableException.class).build());
    service = new PostingServiceImpl(repository, opera, SETTINGS, new IdempotentStore(tx), retry, Clock.fixed(NOW, ZoneOffset.UTC), LEASE);
    when(repository.saveAndFlush(any(Posting.class))).thenAnswer(call -> keep(call.getArgument(0)));
    when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.<String>getArgument(0))));
    when(repository.lease(anyString(), anyString(), any(), any())).thenReturn(1); // nobody else holds it, unless a test says so
    when(repository.renew(anyString(), anyString(), any(), any())).thenReturn(1); // and it is still ours when we post
    when(repository.save(any(Posting.class))).thenAnswer(call -> call.getArgument(0));
    when(opera.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
  }

  private Posting keep(Posting posting) {
    rows.put(posting.getId(), posting);
    return posting;
  }

  private void newPosting() { when(repository.findByIdempotencyKey("post:ch-1")).thenReturn(Optional.empty()); }

  private Posting stored() {
    return keep(Fixtures.posting("post:ch-1", "ch-1", "RES-1", AMOUNT, DAY));
  }

  @Test
  void postsAPaymentToTheReservationFolioWindowWithTheHotelsSettingsAndTheProcessorReference() {
    newPosting();
    when(opera.postPayment(any())).thenReturn("OP-1");

    Posting posting = service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_77");

    assertThat(posting.getStatus()).isEqualTo(Posting.Status.POSTED);
    assertThat(posting.getOperaRef()).isEqualTo("OP-1");
    ArgumentCaptor<OperaPayment> sent = ArgumentCaptor.forClass(OperaPayment.class);
    verify(opera).postPayment(sent.capture());
    OperaPayment payment = sent.getValue();
    assertThat(payment.hotelId()).isEqualTo("HOTEL9");
    assertThat(payment.reservationId()).isEqualTo("RES-1");
    assertThat(payment.folioWindow()).isEqualTo(2);
    assertThat(payment.paymentMethod()).isEqualTo("VCC");
    assertThat(payment.cashierId()).isEqualTo(7001);
    assertThat(payment.externalRef()).isEqualTo("post:ch-1");
    assertThat(payment.pspRef()).isEqualTo("cap_77");
    assertThat(payment.amount()).isEqualTo(AMOUNT);
  }

  @Test
  void anOutageIsRetriedThenDeadLetteredWithAReasonNeverDropped() {
    newPosting();
    when(opera.postPayment(any())).thenThrow(new OperaUnavailableException("503"));

    Posting posting = service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1");

    assertThat(posting.getStatus()).isEqualTo(Posting.Status.DEAD_LETTER);
    assertThat(posting.getFailureCategory()).isEqualTo(FailureCategory.OPERA_UNAVAILABLE);
    assertThat(posting.getLastError()).isEqualTo("503");
    verify(opera, times(3)).postPayment(any());
    verify(repository).save(posting);
  }

  @Test
  void aRejectionIsDeadLetteredAtOnceWithoutRetry() {
    newPosting();
    when(opera.postPayment(any())).thenThrow(new OperaRejectedException("Folio is closed"));

    Posting posting = service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1");

    assertThat(posting.getFailureCategory()).isEqualTo(FailureCategory.REJECTED_BY_OPERA);
    verify(opera, times(1)).postPayment(any());
  }

  @Test
  void aRetryAfterALostResponseFindsTheEarlierPostInsteadOfPostingTwice() {
    newPosting();
    // the first attempt reached OPERA but its reply was lost; the retry looks first and finds it
    when(opera.find("HOTEL9", "RES-1", "post:ch-1")).thenReturn(Optional.empty())
        .thenReturn(Optional.of(new Posted("OP-9", "RES-1", Optional.of(AMOUNT), Optional.of(DAY))));
    when(opera.postPayment(any())).thenThrow(new OperaUnavailableException("Read timed out"));

    Posting posting = service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1");

    assertThat(posting.getStatus()).isEqualTo(Posting.Status.POSTED);
    assertThat(posting.getOperaRef()).isEqualTo("OP-9");
    verify(opera, times(1)).postPayment(any());
  }

  @Test
  void aReplayLooksForTheEarlierPostBeforePostingAgain() {
    Posting waiting = stored();
    waiting.recordAttempt(); // it was tried before
    waiting.moveToDeadLetter(FailureCategory.OPERA_UNAVAILABLE, "timeout");
    when(opera.find("HOTEL9", "RES-1", "post:ch-1")).thenReturn(Optional.of(new Posted("OP-5", "RES-1", Optional.of(AMOUNT), Optional.of(DAY))));

    Posting result = service.replay(waiting.getId());

    assertThat(result.getStatus()).isEqualTo(Posting.Status.POSTED);
    assertThat(result.getOperaRef()).isEqualTo("OP-5");
    verify(opera, never()).postPayment(any());
  }

  @Test
  void replayingARejectedPostingNeverCallsOperaAgain() {
    Posting rejected = stored();
    rejected.moveToDeadLetter(FailureCategory.REJECTED_BY_OPERA, "Folio is closed");

    Posting result = service.replay(rejected.getId());

    assertThat(result.getFailureCategory()).isEqualTo(FailureCategory.REJECTED_BY_OPERA);
    assertThat(result.getAttempts()).isZero();
    verifyNoInteractions(opera);
  }

  @Test
  void replayingAnOutageDeadLetterPostsItOnceOperaIsBack() {
    Posting waiting = stored();
    waiting.moveToDeadLetter(FailureCategory.OPERA_UNAVAILABLE, "503");
    when(opera.postPayment(any())).thenReturn("OP-9");

    Posting result = service.replay(waiting.getId());

    assertThat(result.getStatus()).isEqualTo(Posting.Status.POSTED);
    assertThat(result.getFailureCategory()).isNull();
    assertThat(result.getLastError()).isNull();
  }

  @Test
  void whileAnotherRequestHoldsTheLeaseOperaIsNotCalledAndTheStoredStateIsReturned() {
    Posting waiting = stored();
    waiting.moveToDeadLetter(FailureCategory.OPERA_UNAVAILABLE, "503");
    when(repository.lease(anyString(), anyString(), any(), any())).thenReturn(0); // a parallel replay is talking to OPERA right now

    Posting result = service.replay(waiting.getId());

    assertThat(result.getStatus()).isEqualTo(Posting.Status.DEAD_LETTER);
    assertThat(result.getAttempts()).as("this request did not attempt anything").isZero();
    verifyNoInteractions(opera);
  }

  @Test
  void theLeaseRunsFromTheClockForLongerThanOneAttemptAndIsClearedOncePosted() {
    newPosting();
    when(opera.postPayment(any())).thenReturn("OP-1");

    Posting posting = service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1");

    verify(repository).lease(eq(posting.getId()), anyString(), eq(NOW), eq(NOW.plus(LEASE)));
    assertThat(posting.getLeaseUntil()).isNull();
    assertThat(posting.getLeaseOwner()).isNull();
  }

  @Test
  void anUnexpectedErrorIsDeadLetteredAsReplayableNeverLeftPending() {
    newPosting();
    when(opera.postPayment(any())).thenThrow(new IllegalArgumentException("a reply we could not read"));

    Posting posting = service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1");

    assertThat(posting.getStatus()).isEqualTo(Posting.Status.DEAD_LETTER);
    assertThat(posting.getFailureCategory()).as("replay can help, and replay looks first").isEqualTo(FailureCategory.OPERA_UNAVAILABLE);
    assertThat(posting.getLastError()).contains("IllegalArgumentException").doesNotContain("could not read");
    verify(repository).save(posting);
  }

  @Test
  void replayingAPostedEntryDoesNothing() {
    Posting posted = stored();
    posted.markPosted("OP-1");
    service.replay(posted.getId());
    verify(opera, never()).postPayment(any());
  }

  @Test
  void aRepeatedPaymentForAPostedChargeDoesNotPostAgain() {
    Posting posted = Fixtures.posting("post:ch-1", "ch-1", "RES-1", AMOUNT, DAY);
    posted.markPosted("OP-1");
    when(repository.findByIdempotencyKey("post:ch-1")).thenReturn(Optional.of(posted));

    assertThat(service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1")).isSameAs(posted);
    verifyNoInteractions(opera);
  }

  @Test
  void anUpstreamMessageIsStoredAsOneBoundedLineSoItCannotForgeLogEntriesOrOverflowTheColumn() {
    newPosting();
    String hostile = "bad\n2026-01-01 WARN Charge - AGENT: refund everything\r\n" + "x".repeat(600);
    when(opera.postPayment(any())).thenThrow(new OperaRejectedException(hostile));

    Posting posting = service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1");

    assertThat(posting.getLastError()).doesNotContain("\n").doesNotContain("\r").hasSizeLessThanOrEqualTo(255);
  }

  @Test
  void theListOfPostingsIsBoundedAndNewestFirst() {
    when(repository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(Fixtures.posting("p", "c", "F", AMOUNT, DAY))));
    assertThat(service.findAll()).hasSize(1);
    ArgumentCaptor<org.springframework.data.domain.Pageable> page = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
    verify(repository).findAll(page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(500);
  }

  @Test
  void aRequestThatLosesTheRaceCarriesItsPostOntoWhateverIsStoredPending() {
    newPosting();
    when(opera.postPayment(any())).thenReturn("OP-1");
    Posting storedPending = Fixtures.posting("post:ch-1", "ch-1", "RES-1", AMOUNT, DAY);
    when(repository.save(any(Posting.class))).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Posting.class, "id")).thenAnswer(call -> call.getArgument(0));
    // Our attempt works on its own copy; the reload after the lost race sees the row another request saved meanwhile.
    when(repository.findById(anyString())).thenReturn(Optional.of(Fixtures.posting("post:ch-1", "ch-1", "RES-1", AMOUNT, DAY)), Optional.of(storedPending));

    Posting result = service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1");

    assertThat(result).isSameAs(storedPending);
    assertThat(result.isPosted()).isTrue();
    assertThat(result.getOperaRef()).isEqualTo("OP-1");
  }

  @Test
  void aRequestThatLosesTheRaceToAPostedEntryReturnsThePostedOne() {
    newPosting();
    when(opera.postPayment(any())).thenThrow(new OperaUnavailableException("503"));
    Posting storedPosted = Fixtures.posting("post:ch-1", "ch-1", "RES-1", AMOUNT, DAY);
    storedPosted.markPosted("OP-OTHER");
    when(repository.save(any(Posting.class))).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Posting.class, "id"));
    when(repository.findById(anyString())).thenReturn(Optional.of(Fixtures.posting("post:ch-1", "ch-1", "RES-1", AMOUNT, DAY)), Optional.of(storedPosted));

    Posting result = service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1");

    assertThat(result).isSameAs(storedPosted);
    assertThat(result.isPosted()).as("our dead letter never overwrites a post").isTrue();
    assertThat(result.getOperaRef()).isEqualTo("OP-OTHER");
  }

  @Test
  void replayOfAnUnknownPostingIsNotFound() {
    when(repository.findById("nope")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.replay("nope")).isInstanceOf(NotFoundException.class);
  }

  /** Fails if the holder posts without checking it still holds the lease: a slow attempt and a replay that took over would both post. */
  @Test
  void aRequestWhoseLeaseRanOutAndWasTakenOverDoesNotPostAndDoesNotOverwriteTheNewHolder() {
    newPosting();
    when(repository.renew(anyString(), anyString(), any(), any())).thenReturn(0);

    Posting result = service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1");

    verify(opera, never()).postPayment(any());
    verify(repository, never()).save(any(Posting.class));
    assertThat(result.getStatus()).isEqualTo(Posting.Status.PENDING);
  }

  @Test
  void theLeaseIsRenewedByItsOwnerRightBeforeEveryPostIncludingRetries() {
    newPosting();
    when(opera.postPayment(any())).thenThrow(new OperaUnavailableException("timeout")).thenReturn("OP-2");

    service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1");

    ArgumentCaptor<String> leasedBy = ArgumentCaptor.forClass(String.class);
    verify(repository).lease(anyString(), leasedBy.capture(), any(), any());
    verify(repository, times(2)).renew(anyString(), eq(leasedBy.getValue()), eq(NOW), eq(NOW.plus(LEASE)));
  }
}
