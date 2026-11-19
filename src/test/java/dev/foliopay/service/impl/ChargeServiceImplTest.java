package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.foliopay.Fixtures;
import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.gateway.PspClient.PspResult;
import dev.foliopay.gateway.PspDeclinedException;
import dev.foliopay.gateway.PspRejectedException;
import dev.foliopay.gateway.PspTransientException;
import dev.foliopay.gateway.PspUnreadableReplyException;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.service.BusinessDateService;
import dev.foliopay.service.CardVault;
import dev.foliopay.service.ChargeService.ChargeCommand;
import dev.foliopay.service.support.IdempotentStore;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Pure unit test: the repository, vault and processor are test doubles; the retry is the real Resilience4j one with a 1ms wait. */
@DisplayName("Charges: authorise, capture, retry and decline, with test doubles")
class ChargeServiceImplTest {
  private static final Money TEN = new Money(1_000, "USD");
  private static final Pan PAN = new Pan("4111111111111111");

  private final ChargeRepository repository = mock(ChargeRepository.class);
  private final PspClient psp = mock(PspClient.class);
  private final ScopeTrackingVault vault = new ScopeTrackingVault();
  private final BusinessDateService businessDates = mock(BusinessDateService.class);
  private ChargeServiceImpl service;

  /** A vault double that remembers whether a card number is currently lent out. */
  static class ScopeTrackingVault implements CardVault {
    final AtomicInteger lent = new AtomicInteger();
    final AtomicInteger opened = new AtomicInteger();

    @Override public dev.foliopay.domain.model.CardToken tokenise(Pan pan, String expiry) { throw new UnsupportedOperationException(); }
    boolean known = true;

    @Override public boolean exists(String token) { return known; }

    @Override
    public <T> T withCard(String token, BiFunction<Pan, String, T> use) {
      opened.incrementAndGet();
      lent.incrementAndGet();
      try {
        return use.apply(PAN, "12/29");
      } finally {
        lent.decrementAndGet();
      }
    }
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
    Retry retry = Retry.of("psp", RetryConfig.custom().maxAttempts(3).intervalFunction(IntervalFunction.of(1)).retryExceptions(PspTransientException.class).build());
    service = new ChargeServiceImpl(repository, vault, psp, new IdempotentStore(tx), retry, businessDates);
    when(repository.saveAndFlush(any(Charge.class))).thenAnswer(call -> call.getArgument(0));
    when(repository.save(any(Charge.class))).thenAnswer(call -> call.getArgument(0));
  }

  private static ChargeCommand command() { return Fixtures.command("key-1", "BK-1", "tok_1", TEN); }

  private void noExistingCharge() { when(repository.findByClientIdAndIdempotencyKey(Charge.BRIDGE_CLIENT, "key-1")).thenReturn(Optional.empty()); }

  @Test
  @DisplayName("Authorises, then captures, and stores the processor reference")
  void authorisesThenCapturesAndStoresTheProcessorReference() {
    noExistingCharge();
    when(psp.authorize(endsWith(":auth"), any(), eq("12/29"), eq(TEN))).thenReturn(new PspResult("auth_1"));
    when(psp.capture(endsWith(":cap"), eq("auth_1"), eq(TEN))).thenReturn(new PspResult("cap_1"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.CAPTURED);
    assertThat(charge.getPspRef()).isEqualTo("cap_1");
    // The processor's keys are the charge's own id, not the client's key: unique across clients, the same on every retry (D-3, D-40).
    verify(psp).authorize(eq(charge.getId() + ":auth"), any(), eq("12/29"), eq(TEN));
    verify(psp).capture(charge.getId() + ":cap", "auth_1", TEN);
  }

  @Test
  @DisplayName("A transient failure is retried with the same key until it succeeds")
  void aTransientFailureIsRetriedWithTheSameKeyUntilItSucceeds() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(endsWith(":cap"), eq("auth_1"), eq(TEN))).thenThrow(new PspTransientException("timeout")).thenThrow(new PspTransientException("timeout")).thenReturn(new PspResult("cap_1"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.CAPTURED);
    verify(psp, times(3)).capture(charge.getId() + ":cap", "auth_1", TEN); // same key every time: the processor can deduplicate
    verify(psp, times(1)).authorize(anyString(), any(), anyString(), any());
  }

  @Test
  @DisplayName("When retries run out, the charge stays pending so the same key can try again")
  void whenRetriesRunOutTheChargeStaysPendingSoTheSameKeyCanTryAgain() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenThrow(new PspTransientException("timeout"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.PENDING);
    assertThat(charge.getAttempts()).isEqualTo(1);
    assertThat(charge.getLastError()).isEqualTo("timeout");
    verify(psp, times(3)).capture(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("A capture declined after authorisation releases the hold")
  void aCaptureDeclinedAfterAuthorisationReleasesTheHold() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenThrow(new PspDeclinedException("Capture declined"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.DECLINED);
    verify(psp).voidAuthorization(charge.getId() + ":void", "auth_1");
  }

  @Test
  @DisplayName("An unknown card token is refused before anything is stored or sent")
  void anUnknownCardTokenIsRefusedBeforeAnythingIsStoredOrSent() {
    vault.known = false;
    assertThatThrownBy(() -> service.charge(command())).isInstanceOf(InvalidRequestException.class).hasMessageContaining("Unknown card token");
    verifyNoInteractions(psp);
    verify(repository, never()).saveAndFlush(any(Charge.class));
  }

  @Test
  @DisplayName("A hold that cannot be released does not hide the decline")
  void aHoldThatCannotBeReleasedDoesNotHideTheDecline() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenThrow(new PspDeclinedException("Capture declined"));
    org.mockito.Mockito.doThrow(new RuntimeException("processor down")).when(psp).voidAuthorization(anyString(), anyString());

    assertThat(service.charge(command()).getStatus()).isEqualTo(Charge.Status.DECLINED);
  }

  @Test
  @DisplayName("A request that loses the race carries its capture onto whatever is stored pending")
  void aRequestThatLosesTheRaceCarriesItsCaptureOntoWhateverIsStoredPending() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenReturn(new PspResult("cap_1"));
    Charge storedPending = Fixtures.charge("key-1", "BK-1", "tok_1", TEN);
    when(repository.save(any(Charge.class))).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Charge.class, "id")).thenAnswer(call -> call.getArgument(0));
    when(repository.findById(anyString())).thenReturn(Optional.of(storedPending));

    Charge result = service.charge(command());

    assertThat(result).isSameAs(storedPending);
    assertThat(result.getStatus()).as("the capture was not lost").isEqualTo(Charge.Status.CAPTURED);
    assertThat(result.getPspRef()).isEqualTo("cap_1");
  }

  @Test
  @DisplayName("A request that loses the race to a finished charge returns the finished one")
  void aRequestThatLosesTheRaceToAFinishedChargeReturnsTheFinishedOne() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenReturn(new PspResult("cap_1"));
    Charge storedCaptured = Fixtures.charge("key-1", "BK-1", "tok_1", TEN);
    storedCaptured.markCaptured("cap_other");
    when(repository.save(any(Charge.class))).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(Charge.class, "id"));
    when(repository.findById(anyString())).thenReturn(Optional.of(storedCaptured));

    assertThat(service.charge(command()).getPspRef()).isEqualTo("cap_other");
  }

  @Test
  @DisplayName("A decline at authorisation has nothing to release")
  void aDeclineAtAuthorisationHasNothingToRelease() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenThrow(new PspDeclinedException("Do not honor"));
    service.charge(command());
    verify(psp, never()).voidAuthorization(anyString(), anyString());
  }

  @Test
  @DisplayName("The same key with a different folio or business date is a conflict, not a silent replay of the first")
  void theSameKeyWithADifferentFolioOrBusinessDateIsAConflictNotASilentReplayOfTheFirst() {
    when(repository.findByClientIdAndIdempotencyKey(Charge.BRIDGE_CLIENT, "key-1")).thenReturn(Optional.of(Fixtures.charge("key-1", "BK-1", "tok_1", TEN)));

    assertThatThrownBy(() -> service.charge(new ChargeCommand(Charge.BRIDGE_CLIENT, "key-1", "BK-1", "F-OTHER", Fixtures.DAY, "tok_1", TEN))).isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> service.charge(new ChargeCommand(Charge.BRIDGE_CLIENT, "key-1", "BK-1", "F-1", Fixtures.DAY.plusDays(1), "tok_1", TEN))).isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> service.charge(new ChargeCommand(Charge.BRIDGE_CLIENT, "key-1", "BK-OTHER", "F-1", Fixtures.DAY, "tok_1", TEN))).isInstanceOf(ConflictException.class);
    verifyNoInteractions(psp);
  }

  @Test
  @DisplayName("A new charge without a date takes the hotel's business date from the PMS")
  void aNewChargeWithoutADateTakesTheHotelsBusinessDateFromThePms() {
    noExistingCharge();
    when(businessDates.resolve(null)).thenReturn(Fixtures.DAY.plusDays(3));
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenReturn(new PspResult("cap_1"));

    Charge charge = service.charge(new ChargeCommand(Charge.BRIDGE_CLIENT, "key-1", "BK-1", "F-1", null, "tok_1", TEN));

    assertThat(charge.getBusinessDate()).isEqualTo(Fixtures.DAY.plusDays(3));
  }

  @Test
  @DisplayName("A pending charge is resumed after night audit when the repeat names no date, and keeps its own date")
  void aPendingChargeIsResumedAfterNightAuditWhenTheRepeatNamesNoDateAndKeepsItsOwnDate() {
    Charge pending = Fixtures.charge("key-1", "BK-1", "tok_1", TEN); // taken on DAY, the capture reply was lost
    when(repository.findByClientIdAndIdempotencyKey(Charge.BRIDGE_CLIENT, "key-1")).thenReturn(Optional.of(pending));
    when(businessDates.resolve(any())).thenReturn(Fixtures.DAY.plusDays(1)); // night audit has run since
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenReturn(new PspResult("cap_1"));

    Charge charge = service.charge(new ChargeCommand(Charge.BRIDGE_CLIENT, "key-1", "BK-1", "F-1", null, "tok_1", TEN));

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.CAPTURED);
    assertThat(charge.getBusinessDate()).as("the date it was taken on").isEqualTo(Fixtures.DAY);
    verify(psp).capture(eq(pending.getId() + ":cap"), anyString(), any());
    verifyNoInteractions(businessDates); // a repeat never asks the PMS: it resumes even while the PMS is down
  }

  @Test
  @DisplayName("A decline is final and not retried")
  void aDeclineIsFinalAndNotRetried() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenThrow(new PspDeclinedException("Do not honor"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.DECLINED);
    verify(psp, times(1)).authorize(anyString(), any(), anyString(), any());
    verify(psp, never()).capture(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("The card number is not held while a retry waits or during capture")
  void theCardNumberIsNotHeldWhileARetryWaitsOrDuringCapture() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any()))
        .thenAnswer(call -> { assertThat(vault.lent).as("card lent during authorise").hasValue(1); throw new PspTransientException("timeout"); })
        .thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenAnswer(call -> { assertThat(vault.lent).as("card not lent during capture").hasValue(0); return new PspResult("cap_1"); });

    service.charge(command());

    assertThat(vault.opened).as("the vault was opened once per attempt, not once around all of them").hasValue(2);
    assertThat(vault.lent).hasValue(0);
  }

  @Test
  @DisplayName("A captured charge is returned without touching the processor")
  void aCapturedChargeIsReturnedWithoutTouchingTheProcessor() {
    Charge done = Fixtures.charge("key-1", "BK-1", "tok_1", TEN);
    done.markCaptured("cap_1");
    when(repository.findByClientIdAndIdempotencyKey(Charge.BRIDGE_CLIENT, "key-1")).thenReturn(Optional.of(done));

    assertThat(service.charge(command())).isSameAs(done);
    verifyNoInteractions(psp);
  }

  @Test
  @DisplayName("The same key with a different amount is a conflict and never reaches the processor")
  void theSameKeyWithADifferentAmountIsAConflictAndNeverReachesTheProcessor() {
    Charge existing = Fixtures.charge("key-1", "BK-1", "tok_1", TEN);
    when(repository.findByClientIdAndIdempotencyKey(Charge.BRIDGE_CLIENT, "key-1")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.charge(Fixtures.command("key-1", "BK-1", "tok_1", new Money(9_999, "USD"))))
        .isInstanceOf(ConflictException.class).hasMessageContaining("different parameters");
    verifyNoInteractions(psp);
  }

  @Test
  @DisplayName("The same key with a different card is a conflict")
  void theSameKeyWithADifferentCardIsAConflict() {
    when(repository.findByClientIdAndIdempotencyKey(Charge.BRIDGE_CLIENT, "key-1")).thenReturn(Optional.of(Fixtures.charge("key-1", "BK-1", "tok_1", TEN)));
    assertThatThrownBy(() -> service.charge(Fixtures.command("key-1", "BK-1", "tok_other", TEN))).isInstanceOf(ConflictException.class);
  }

  @Test
  @DisplayName("A zero amount is refused before anything is stored")
  void aZeroAmountIsRefusedBeforeAnythingIsStored() {
    assertThatThrownBy(() -> service.charge(Fixtures.command("key-1", "BK-1", "tok_1", new Money(0, "USD")))).isInstanceOf(InvalidRequestException.class);
    verifyNoInteractions(repository, psp);
  }

  /** Fails if a refusal of the request (not the card) escapes uncaught: nothing saved, the hold never released, a 500 on every retry. */
  @Test
  @DisplayName("A capture the processor refuses is final and releases the hold")
  void aCaptureTheProcessorRefusesIsFinalAndReleasesTheHold() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenThrow(new PspRejectedException("The processor refused the request (422): AMOUNT_MISMATCH"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.DECLINED);
    assertThat(charge.getLastError()).contains("AMOUNT_MISMATCH");
    verify(psp).voidAuthorization(charge.getId() + ":void", "auth_1");
    verify(psp, times(1)).capture(anyString(), anyString(), any());
    verify(repository).save(charge);
  }

  /** Fails if an unexpected error is dropped (nothing saved) or treated as a decline (the hold released although the capture may have landed). */
  @Test
  @DisplayName("An unexpected error is saved as pending and keeps the hold, because the capture may have happened")
  void anUnexpectedErrorIsSavedAsPendingAndKeepsTheHoldBecauseTheCaptureMayHaveHappened() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenThrow(new IllegalStateException("unreadable reply"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.PENDING);
    assertThat(charge.getLastError()).isEqualTo("Unexpected error: IllegalStateException");
    verify(psp, never()).voidAuthorization(anyString(), anyString());
    verify(repository).save(charge);
  }

  /** Fails if a success without a reference is recorded as captured (blank reference) or declined (the hold released): it may have happened. */
  @Test
  @DisplayName("A capture answered without a reference stays pending and keeps the hold")
  void aCaptureAnsweredWithoutAReferenceStaysPendingAndKeepsTheHold() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenThrow(new PspUnreadableReplyException("The processor accepted the capture but sent no reference"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.PENDING);
    assertThat(charge.getPspRef()).isNull();
    assertThat(charge.getLastError()).contains("sent no reference");
    verify(psp, never()).voidAuthorization(anyString(), anyString());
    verify(psp, times(1)).capture(anyString(), anyString(), any()); // not retried: the same key would get the same unreadable answer
  }

  /** Fails if the lookup ignores the client: another client's charge under the same key would be returned (and its result read). */
  @Test
  @DisplayName("A key is looked up in the caller's own namespace")
  void aKeyIsLookedUpInTheCallersOwnNamespace() {
    when(repository.findByClientIdAndIdempotencyKey("client-b", "key-1")).thenReturn(Optional.empty());
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenReturn(new PspResult("cap_1"));

    Charge charge = service.charge(new ChargeCommand("client-b", "key-1", "BK-1", "F-1", Fixtures.DAY, "tok_1", TEN));

    assertThat(charge.getClientId()).isEqualTo("client-b");
    verify(repository, never()).findByClientIdAndIdempotencyKey(eq(Charge.BRIDGE_CLIENT), anyString());
  }

  /** How a caller learns whether money moved after a failed request: by its own key, never by another client's. */
  @Test
  @DisplayName("A stored charge is found under its client and key")
  void aStoredChargeIsFoundUnderItsClientAndKey() {
    Charge stored = Fixtures.charge("key-1", "BK-1", "tok_1", TEN);
    when(repository.findByClientIdAndIdempotencyKey(Charge.BRIDGE_CLIENT, "key-1")).thenReturn(Optional.of(stored));

    assertThat(service.find(Charge.BRIDGE_CLIENT, "key-1")).contains(stored);
    assertThat(service.find("client-b", "key-1")).isEmpty();
  }

  @Test
  @DisplayName("A new charge looks its key up once before inserting")
  void aNewChargeLooksItsKeyUpOnceBeforeInserting() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenReturn(new PspResult("cap_1"));

    service.charge(command());

    verify(repository, times(1)).findByClientIdAndIdempotencyKey(Charge.BRIDGE_CLIENT, "key-1");
  }
}
