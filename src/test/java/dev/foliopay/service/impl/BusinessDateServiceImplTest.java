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
}
