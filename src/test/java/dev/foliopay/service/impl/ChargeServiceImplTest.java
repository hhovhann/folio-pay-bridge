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

import dev.foliopay.domain.exception.ConflictException;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.Fixtures;
import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.gateway.PspClient.PspResult;
import dev.foliopay.gateway.PspDeclinedException;
import dev.foliopay.gateway.PspTransientException;
import dev.foliopay.repository.ChargeRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Pure unit test: the repository, vault and processor are test doubles; the retry is the real Resilience4j one with a 1ms wait. */
class ChargeServiceImplTest {
  private static final Money TEN = new Money(1_000, "USD");
  private static final Pan PAN = new Pan("4111111111111111");

  private final ChargeRepository repository = mock(ChargeRepository.class);
  private final PspClient psp = mock(PspClient.class);
  private final ScopeTrackingVault vault = new ScopeTrackingVault();
  private ChargeServiceImpl service;

  /** A vault double that remembers whether a card number is currently lent out. */
  static class ScopeTrackingVault implements CardVault {
    final AtomicInteger lent = new AtomicInteger();
    final AtomicInteger opened = new AtomicInteger();

    @Override public dev.foliopay.domain.model.CardToken tokenise(Pan pan, String expiry) { throw new UnsupportedOperationException(); }
    @Override public boolean exists(String token) { return true; }

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
    service = new ChargeServiceImpl(repository, vault, psp, new IdempotentStore(tx), retry);
    when(repository.saveAndFlush(any(Charge.class))).thenAnswer(call -> call.getArgument(0));
    when(repository.save(any(Charge.class))).thenAnswer(call -> call.getArgument(0));
  }

  private static ChargeCommand command() { return Fixtures.command("key-1", "BK-1", "tok_1", TEN); }

  private void noExistingCharge() { when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty()); }

  @Test
  void authorisesThenCapturesAndStoresTheProcessorReference() {
    noExistingCharge();
    when(psp.authorize(eq("key-1:auth"), any(), eq("12/29"), eq(TEN))).thenReturn(new PspResult("auth_1"));
    when(psp.capture("key-1:cap", "auth_1", TEN)).thenReturn(new PspResult("cap_1"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.CAPTURED);
    assertThat(charge.getPspRef()).isEqualTo("cap_1");
  }

  @Test
  void aTransientFailureIsRetriedWithTheSameKeyUntilItSucceeds() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture("key-1:cap", "auth_1", TEN)).thenThrow(new PspTransientException("timeout")).thenThrow(new PspTransientException("timeout")).thenReturn(new PspResult("cap_1"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.CAPTURED);
    verify(psp, times(3)).capture("key-1:cap", "auth_1", TEN); // same key every time: the processor can deduplicate
    verify(psp, times(1)).authorize(anyString(), any(), anyString(), any());
  }

  @Test
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
  void aCaptureDeclinedAfterAuthorisationReleasesTheHold() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenReturn(new PspResult("auth_1"));
    when(psp.capture(anyString(), anyString(), any())).thenThrow(new PspDeclinedException("Capture declined"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.DECLINED);
    verify(psp).voidAuthorization("key-1:void", "auth_1");
  }

  @Test
  void aDeclineAtAuthorisationHasNothingToRelease() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenThrow(new PspDeclinedException("Do not honor"));
    service.charge(command());
    verify(psp, never()).voidAuthorization(anyString(), anyString());
  }

  @Test
  void theSameKeyWithADifferentFolioOrBusinessDateIsAConflictNotASilentReplayOfTheFirst() {
    when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(Fixtures.charge("key-1", "BK-1", "tok_1", TEN)));

    assertThatThrownBy(() -> service.charge(new ChargeCommand("key-1", "BK-1", "F-OTHER", Fixtures.DAY, "tok_1", TEN))).isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> service.charge(new ChargeCommand("key-1", "BK-1", "F-1", Fixtures.DAY.plusDays(1), "tok_1", TEN))).isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> service.charge(new ChargeCommand("key-1", "BK-OTHER", "F-1", Fixtures.DAY, "tok_1", TEN))).isInstanceOf(ConflictException.class);
    verifyNoInteractions(psp);
  }

  @Test
  void aDeclineIsFinalAndNotRetried() {
    noExistingCharge();
    when(psp.authorize(anyString(), any(), anyString(), any())).thenThrow(new PspDeclinedException("Do not honor"));

    Charge charge = service.charge(command());

    assertThat(charge.getStatus()).isEqualTo(Charge.Status.DECLINED);
    verify(psp, times(1)).authorize(anyString(), any(), anyString(), any());
    verify(psp, never()).capture(anyString(), anyString(), any());
  }

  @Test
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
  void aCapturedChargeIsReturnedWithoutTouchingTheProcessor() {
    Charge done = Fixtures.charge("key-1", "BK-1", "tok_1", TEN);
    done.captured("cap_1");
    when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(done));

    assertThat(service.charge(command())).isSameAs(done);
    verifyNoInteractions(psp);
  }

  @Test
  void theSameKeyWithADifferentAmountIsAConflictAndNeverReachesTheProcessor() {
    Charge existing = Fixtures.charge("key-1", "BK-1", "tok_1", TEN);
    when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.charge(Fixtures.command("key-1", "BK-1", "tok_1", new Money(9_999, "USD"))))
        .isInstanceOf(ConflictException.class).hasMessageContaining("different parameters");
    verifyNoInteractions(psp);
  }

  @Test
  void theSameKeyWithADifferentCardIsAConflict() {
    when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(Fixtures.charge("key-1", "BK-1", "tok_1", TEN)));
    assertThatThrownBy(() -> service.charge(Fixtures.command("key-1", "BK-1", "tok_other", TEN))).isInstanceOf(ConflictException.class);
  }

  @Test
  void aZeroAmountIsRefusedBeforeAnythingIsStored() {
    assertThatThrownBy(() -> service.charge(Fixtures.command("key-1", "BK-1", "tok_1", new Money(0, "USD")))).isInstanceOf(InvalidRequestException.class);
    verifyNoInteractions(repository, psp);
  }
}
