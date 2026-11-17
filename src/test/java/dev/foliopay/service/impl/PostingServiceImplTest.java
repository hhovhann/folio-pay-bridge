package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class PostingServiceImplTest {
  private static final Money AMOUNT = new Money(5_000, "USD");
  private static final LocalDate DAY = Fixtures.DAY;
  private static final OperaProperties SETTINGS = new OperaProperties("HOTEL9", 2, "VCC", "CASH1");

  private final PostingRepository repository = mock(PostingRepository.class);
  private final OperaClient opera = mock(OperaClient.class);
  private PostingServiceImpl service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
    Retry retry = Retry.of("folio", RetryConfig.custom().maxAttempts(3).intervalFunction(IntervalFunction.of(1)).retryExceptions(OperaUnavailableException.class).build());
    service = new PostingServiceImpl(repository, opera, SETTINGS, new IdempotentStore(tx), retry);
    when(repository.saveAndFlush(any(Posting.class))).thenAnswer(call -> call.getArgument(0));
    when(repository.save(any(Posting.class))).thenAnswer(call -> call.getArgument(0));
    when(opera.find(anyString(), anyString())).thenReturn(Optional.empty());
  }

  private void newPosting() { when(repository.findByIdempotencyKey("post:ch-1")).thenReturn(Optional.empty()); }

  private Posting stored() {
    Posting posting = Fixtures.posting("post:ch-1", "ch-1", "RES-1", AMOUNT, DAY);
    when(repository.findById(posting.getId())).thenReturn(Optional.of(posting));
    return posting;
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
    assertThat(payment.paymentCode()).isEqualTo("VCC");
    assertThat(payment.cashierId()).isEqualTo("CASH1");
    assertThat(payment.externalRef()).isEqualTo("post:ch-1");
    assertThat(payment.pspRef()).isEqualTo("cap_77");
    assertThat(payment.amount()).isEqualTo(AMOUNT);
    assertThat(payment.businessDate()).isEqualTo(DAY);
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
    when(opera.find("HOTEL9", "post:ch-1")).thenReturn(Optional.empty()).thenReturn(Optional.of(new Posted("OP-9", "RES-1", AMOUNT, DAY)));
    when(opera.postPayment(any())).thenThrow(new OperaUnavailableException("Read timed out"));

    Posting posting = service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1");

    assertThat(posting.getStatus()).isEqualTo(Posting.Status.POSTED);
    assertThat(posting.getOperaRef()).isEqualTo("OP-9");
    verify(opera, times(1)).postPayment(any());
  }

  @Test
  void aReplayLooksForTheEarlierPostBeforePostingAgain() {
    Posting waiting = stored();
    waiting.attempt(); // it was tried before
    waiting.deadLetter(FailureCategory.OPERA_UNAVAILABLE, "timeout");
    when(opera.find("HOTEL9", "post:ch-1")).thenReturn(Optional.of(new Posted("OP-5", "RES-1", AMOUNT, DAY)));

    Posting result = service.replay(waiting.getId());

    assertThat(result.getStatus()).isEqualTo(Posting.Status.POSTED);
    assertThat(result.getOperaRef()).isEqualTo("OP-5");
    verify(opera, never()).postPayment(any());
  }

  @Test
  void replayingARejectedPostingNeverCallsOperaAgain() {
    Posting rejected = stored();
    rejected.deadLetter(FailureCategory.REJECTED_BY_OPERA, "Folio is closed");

    Posting result = service.replay(rejected.getId());

    assertThat(result.getFailureCategory()).isEqualTo(FailureCategory.REJECTED_BY_OPERA);
    assertThat(result.getAttempts()).isZero();
    verifyNoInteractions(opera);
  }

  @Test
  void replayingAnOutageDeadLetterPostsItOnceOperaIsBack() {
    Posting waiting = stored();
    waiting.deadLetter(FailureCategory.OPERA_UNAVAILABLE, "503");
    when(opera.postPayment(any())).thenReturn("OP-9");

    Posting result = service.replay(waiting.getId());

    assertThat(result.getStatus()).isEqualTo(Posting.Status.POSTED);
    assertThat(result.getFailureCategory()).isNull();
    assertThat(result.getLastError()).isNull();
  }

  @Test
  void replayingAPostedEntryDoesNothing() {
    Posting posted = stored();
    posted.posted("OP-1");
    service.replay(posted.getId());
    verify(opera, never()).postPayment(any());
  }

  @Test
  void aRepeatedPaymentForAPostedChargeDoesNotPostAgain() {
    Posting posted = Fixtures.posting("post:ch-1", "ch-1", "RES-1", AMOUNT, DAY);
    posted.posted("OP-1");
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
    when(repository.findById(anyString())).thenReturn(Optional.of(storedPending));

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
    storedPosted.posted("OP-OTHER");
    when(repository.save(any(Posting.class))).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Posting.class, "id"));
    when(repository.findById(anyString())).thenReturn(Optional.of(storedPosted));

    assertThat(service.post("ch-1", "RES-1", AMOUNT, DAY, "cap_1").getOperaRef()).isEqualTo("OP-OTHER");
  }

  @Test
  void replayOfAnUnknownPostingIsNotFound() {
    when(repository.findById("nope")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.replay("nope")).isInstanceOf(NotFoundException.class);
  }
}
