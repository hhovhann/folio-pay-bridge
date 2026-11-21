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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Business date: from the PMS, through its outages and slow replies")
class BusinessDateServiceImplTest {
  private static final LocalDate PMS_DATE = LocalDate.of(2026, 11, 16);
  private final OperaClient opera = mock(OperaClient.class);
  private final OperaProperties settings = new OperaProperties("H1", 1, "CC", 9001, Duration.ofHours(24));

  private BusinessDateServiceImpl service(Clock clock) { return new BusinessDateServiceImpl(opera, settings, clock); }

  @Test
  @DisplayName("The business date comes from the PMS, not the wall clock")
  void theDateComesFromThePmsNotTheWallClock() {
    when(opera.businessDate("H1")).thenReturn(PMS_DATE);
    Clock wall = Clock.fixed(Instant.parse("2026-11-17T01:00:00Z"), ZoneOffset.UTC); // after midnight, before night audit
    assertThat(service(wall).resolve(null)).isEqualTo(PMS_DATE);
  }

  @Test
  @DisplayName("A different date from the client is refused")
  void aDifferentDateFromTheClientIsRefused() {
    when(opera.businessDate("H1")).thenReturn(PMS_DATE);
    assertThatThrownBy(() -> service(Clock.systemUTC()).resolve(PMS_DATE.plusDays(1))).isInstanceOf(InvalidRequestException.class).hasMessageContaining(PMS_DATE.toString());
  }

  @Test
  @DisplayName("When the PMS is down, the last known date keeps the payment flowing")
  void whenThePmsIsDownTheLastKnownDateKeepsThePaymentFlowing() {
    BusinessDateServiceImpl service = service(Clock.systemUTC());
    when(opera.businessDate(anyString())).thenReturn(PMS_DATE).thenThrow(new OperaUnavailableException("503"));

    assertThat(service.resolve(null)).isEqualTo(PMS_DATE);
    assertThat(service.resolve(null)).as("PMS is down: the earlier answer is used").isEqualTo(PMS_DATE);
  }

  @Test
  @DisplayName("With no earlier answer, a PMS outage is not hidden")
  void withNoEarlierAnswerAPmsOutageIsNotHidden() {
    when(opera.businessDate(anyString())).thenThrow(new OperaUnavailableException("503"));
    assertThatThrownBy(() -> service(Clock.systemUTC()).resolve(null)).isInstanceOf(OperaUnavailableException.class);
  }

  @Test
  @DisplayName("An answer older than a day is not trusted")
  void anAnswerOlderThanADayIsNotTrusted() {
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T10:00:00Z"));
    Clock moving = new Clock() {
      @Override public ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(ZoneId zone) { return this; }
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
  @DisplayName("A slow PMS answer for one request does not hold up another")
  void aSlowPmsAnswerForOneRequestDoesNotHoldUpAnother() throws Exception {
    CountDownLatch firstIsStuck = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    when(opera.businessDate(anyString())).thenAnswer(call -> {
      if (calls.incrementAndGet() == 1) {
        firstIsStuck.countDown();
        release.await(5, TimeUnit.SECONDS); // a black-holed PMS connection
      }
      return PMS_DATE;
    });
    BusinessDateServiceImpl service = service(Clock.systemUTC());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    var slow = pool.submit(() -> service.resolve(null));
    firstIsStuck.await(5, TimeUnit.SECONDS);

    var fast = pool.submit(() -> service.resolve(null));
    assertThat(fast.get(2, TimeUnit.SECONDS)).as("answered while the first is still waiting").isEqualTo(PMS_DATE);
    assertThat(slow.isDone()).isFalse();

    release.countDown();
    assertThat(slow.get(5, TimeUnit.SECONDS)).isEqualTo(PMS_DATE);
    pool.shutdown();
  }

  /** A clock a test can move. */
  private static final class MovingClock extends Clock {
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T10:00:00Z"));
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
    void advance(Duration by) { now.set(now.get().plus(by)); }
  }

  /** Fails if the last reply to arrive wins: a slow reply from before night audit would put the fallback back to the old date. */
  @Test
  @DisplayName("A slow reply from before night audit cannot move the remembered date back")
  void aSlowReplyFromBeforeNightAuditCannotMoveTheRememberedDateBack() throws Exception {
    MovingClock clock = new MovingClock();
    BusinessDateServiceImpl service = service(clock);
    CountDownLatch oldAnswerReady = new CountDownLatch(1);
    CountDownLatch newDateStored = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    when(opera.businessDate(anyString())).thenAnswer(call -> switch (calls.incrementAndGet()) {
      case 1 -> { oldAnswerReady.countDown(); newDateStored.await(5, TimeUnit.SECONDS); yield PMS_DATE; } // asked before audit, answers late
      case 2 -> PMS_DATE.plusDays(1); // asked after audit
      default -> throw new OperaUnavailableException("503");
    });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    var slow = pool.submit(() -> service.resolve(null));
    oldAnswerReady.await(5, TimeUnit.SECONDS);
    assertThat(service.resolve(null)).isEqualTo(PMS_DATE.plusDays(1));
    newDateStored.countDown();

    assertThat(slow.get(5, TimeUnit.SECONDS)).as("business dates never go back").isEqualTo(PMS_DATE.plusDays(1));
    clock.advance(Duration.ofMinutes(5));
    assertThat(service.resolve(null)).as("the outage fallback is the newer date").isEqualTo(PMS_DATE.plusDays(1));
    pool.shutdown();
  }

  /**
   * Fails if the PMS is skipped for a while after a failure: a night audit that ran during the outage would be missed, the next payments
   * would carry yesterday's date, and OPERA rejects those postings for good. Found by the full-flow demo's dry run (agent log 39).
   */
  @Test
  @DisplayName("The first answer after an outage is used at once, so a night audit during the outage is not missed")
  void theFirstAnswerAfterAnOutageIsUsedAtOnceSoANightAuditDuringTheOutageIsNotMissed() {
    BusinessDateServiceImpl service = service(new MovingClock());
    when(opera.businessDate(anyString())).thenReturn(PMS_DATE).thenThrow(new OperaUnavailableException("timeout")).thenReturn(PMS_DATE.plusDays(1));
    service.resolve(null);
    assertThat(service.resolve(null)).as("down: the last known date").isEqualTo(PMS_DATE);

    assertThat(service.resolve(null)).as("back, after a night audit: the new date at once").isEqualTo(PMS_DATE.plusDays(1));
  }

  /** Fails if the rule is "the latest date wins": a date the hotel corrected backwards would be ignored until a restart. */
  @Test
  @DisplayName("A newer answer wins even when it is an earlier date, because the PMS is the authority")
  void aNewerAnswerWinsEvenWhenItIsAnEarlierDateBecauseThePmsIsTheAuthority() {
    BusinessDateServiceImpl service = service(new MovingClock());
    when(opera.businessDate(anyString())).thenReturn(PMS_DATE.plusDays(1)).thenReturn(PMS_DATE);

    service.resolve(null);

    assertThat(service.resolve(null)).as("the PMS corrected its date: the bridge follows").isEqualTo(PMS_DATE);
  }
}

