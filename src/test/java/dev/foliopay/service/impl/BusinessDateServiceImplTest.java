package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.foliopay.config.OperaProperties;
import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.gateway.OperaClient;
import dev.foliopay.gateway.OperaUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BusinessDateServiceImplTest {
  private static final LocalDate PMS_DATE = LocalDate.of(2026, 11, 16);
  private final OperaClient opera = mock(OperaClient.class);
  private final OperaProperties settings = new OperaProperties("H1", 1, "CC", "C");

  private BusinessDateServiceImpl service(Clock clock) { return new BusinessDateServiceImpl(opera, settings, clock); }

  @Test
  void theDateComesFromThePmsNotTheWallClock() {
    when(opera.businessDate("H1")).thenReturn(PMS_DATE);
    Clock wall = Clock.fixed(Instant.parse("2026-11-17T01:00:00Z"), ZoneOffset.UTC); // after midnight, before night audit
    assertThat(service(wall).resolve(null)).isEqualTo(PMS_DATE);
  }

  @Test
  void aDifferentDateFromTheClientIsRefused() {
    when(opera.businessDate("H1")).thenReturn(PMS_DATE);
    assertThatThrownBy(() -> service(Clock.systemUTC()).resolve(PMS_DATE.plusDays(1))).isInstanceOf(InvalidRequestException.class).hasMessageContaining(PMS_DATE.toString());
  }

  @Test
  void whenThePmsIsDownTheLastKnownDateKeepsThePaymentFlowing() {
    BusinessDateServiceImpl service = service(Clock.systemUTC());
    when(opera.businessDate(anyString())).thenReturn(PMS_DATE).thenThrow(new OperaUnavailableException("503"));

    assertThat(service.resolve(null)).isEqualTo(PMS_DATE);
    assertThat(service.resolve(null)).as("PMS is down: the earlier answer is used").isEqualTo(PMS_DATE);
  }

  @Test
  void withNoEarlierAnswerAPmsOutageIsNotHidden() {
    when(opera.businessDate(anyString())).thenThrow(new OperaUnavailableException("503"));
    assertThatThrownBy(() -> service(Clock.systemUTC()).resolve(null)).isInstanceOf(OperaUnavailableException.class);
  }

  @Test
  void anAnswerOlderThanADayIsNotTrusted() {
    java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-11-16T10:00:00Z"));
    Clock moving = new Clock() {
      @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId zone) { return this; }
      @Override public Instant instant() { return now.get(); }
    };
    BusinessDateServiceImpl service = service(moving);
    when(opera.businessDate(anyString())).thenReturn(PMS_DATE).thenThrow(new OperaUnavailableException("503"));
    service.resolve(null);
    now.set(Instant.parse("2026-11-17T11:00:00Z"));

    assertThatThrownBy(() -> service.resolve(null)).isInstanceOf(OperaUnavailableException.class);
  }

  /** Review finding M3: the PMS call used to sit inside a synchronized method, so one slow answer made every payment wait in line. */
  @Test
  void aSlowPmsAnswerForOneRequestDoesNotHoldUpAnother() throws Exception {
    java.util.concurrent.CountDownLatch firstIsStuck = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    when(opera.businessDate(anyString())).thenAnswer(call -> {
      if (calls.incrementAndGet() == 1) {
        firstIsStuck.countDown();
        release.await(5, java.util.concurrent.TimeUnit.SECONDS); // a black-holed PMS connection
      }
      return PMS_DATE;
    });
    BusinessDateServiceImpl service = service(Clock.systemUTC());
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var slow = pool.submit(() -> service.resolve(null));
    firstIsStuck.await(5, java.util.concurrent.TimeUnit.SECONDS);

    var fast = pool.submit(() -> service.resolve(null));
    assertThat(fast.get(2, java.util.concurrent.TimeUnit.SECONDS)).as("answered while the first is still waiting").isEqualTo(PMS_DATE);
    assertThat(slow.isDone()).isFalse();

    release.countDown();
    assertThat(slow.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(PMS_DATE);
    pool.shutdown();
  }

  /** A clock a test can move. */
  private static final class MovingClock extends Clock {
    final java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-11-16T10:00:00Z"));
    @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
    void advance(java.time.Duration by) { now.set(now.get().plus(by)); }
  }

  /** Fails if the last reply to arrive wins: a slow reply from before night audit would put the fallback back to the old date. */
  @Test
  void aSlowReplyFromBeforeNightAuditCannotMoveTheRememberedDateBack() throws Exception {
    MovingClock clock = new MovingClock();
    BusinessDateServiceImpl service = service(clock);
    java.util.concurrent.CountDownLatch oldAnswerReady = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch newDateStored = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    when(opera.businessDate(anyString())).thenAnswer(call -> switch (calls.incrementAndGet()) {
      case 1 -> { oldAnswerReady.countDown(); newDateStored.await(5, java.util.concurrent.TimeUnit.SECONDS); yield PMS_DATE; } // asked before audit, answers late
      case 2 -> PMS_DATE.plusDays(1); // asked after audit
      default -> throw new OperaUnavailableException("503");
    });
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
    var slow = pool.submit(() -> service.resolve(null));
    oldAnswerReady.await(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(service.resolve(null)).isEqualTo(PMS_DATE.plusDays(1));
    newDateStored.countDown();

    assertThat(slow.get(5, java.util.concurrent.TimeUnit.SECONDS)).as("business dates never go back").isEqualTo(PMS_DATE.plusDays(1));
    clock.advance(java.time.Duration.ofMinutes(5));
    assertThat(service.resolve(null)).as("the outage fallback is the newer date").isEqualTo(PMS_DATE.plusDays(1));
    pool.shutdown();
  }

  /**
   * Fails if the PMS is skipped for a while after a failure: a night audit that ran during the outage would be missed, the next payments
   * would carry yesterday's date, and OPERA rejects those postings for good. Found by the full-flow demo's dry run (agent log 39).
   */
  @Test
  void theFirstAnswerAfterAnOutageIsUsedAtOnceSoANightAuditDuringTheOutageIsNotMissed() {
    BusinessDateServiceImpl service = service(new MovingClock());
    when(opera.businessDate(anyString())).thenReturn(PMS_DATE).thenThrow(new OperaUnavailableException("timeout")).thenReturn(PMS_DATE.plusDays(1));
    service.resolve(null);
    assertThat(service.resolve(null)).as("down: the last known date").isEqualTo(PMS_DATE);

    assertThat(service.resolve(null)).as("back, after a night audit: the new date at once").isEqualTo(PMS_DATE.plusDays(1));
  }

  /** Fails if the rule is "the latest date wins": a date the hotel corrected backwards would be ignored until a restart. */
  @Test
  void aNewerAnswerWinsEvenWhenItIsAnEarlierDateBecauseThePmsIsTheAuthority() {
    BusinessDateServiceImpl service = service(new MovingClock());
    when(opera.businessDate(anyString())).thenReturn(PMS_DATE.plusDays(1)).thenReturn(PMS_DATE);

    service.resolve(null);

    assertThat(service.resolve(null)).as("the PMS corrected its date: the bridge follows").isEqualTo(PMS_DATE);
  }
}

