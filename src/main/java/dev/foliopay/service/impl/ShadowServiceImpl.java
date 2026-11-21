package dev.foliopay.service.impl;

import dev.foliopay.config.OperaProperties;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.ShadowBooking;
import dev.foliopay.domain.model.ShadowConfig;
import dev.foliopay.domain.model.ShadowReport;
import dev.foliopay.domain.model.ShadowReport.Mismatch;
import dev.foliopay.domain.rule.LegacyRules;
import dev.foliopay.domain.rule.Pricing;
import dev.foliopay.gateway.OperaClient;
import dev.foliopay.service.CardVault;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.PaymentCommand;
import dev.foliopay.service.ShadowService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "foliopay.gateway", havingValue = "fake")
class ShadowServiceImpl implements ShadowService {
  private static final Logger log = LoggerFactory.getLogger(ShadowServiceImpl.class);
  private static final List<String> ZONES = List.of("Asia/Dubai", "America/New_York", "Europe/London", "Asia/Tokyo", "Australia/Sydney");
  private static final int MAX_SAMPLES = 12;
  private static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");
  private static final int SPREAD_DAYS = 20;
  private final PaymentService payments;
  private final CardVault vault;
  private final OperaClient opera;
  private final OperaProperties settings;

  ShadowServiceImpl(PaymentService payments, CardVault vault, OperaClient opera, OperaProperties settings) {
    this.settings = settings;
    this.payments = payments;
    this.vault = vault;
    this.opera = opera;
  }

  @Override
  public List<ShadowBooking> generate(long seed, int count) {
    log.debug("generate(seed={}, count={})", seed, count);
    Random random = new Random(seed);
    List<ShadowBooking> bookings = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      bookings.add(
          new ShadowBooking(
              "BK-" + (1000 + i),
              1 + random.nextInt(5),
              8_000 + random.nextInt(37_000),
              700 + random.nextInt(900),
              "USD",
              ZoneId.of(ZONES.get(random.nextInt(ZONES.size()))),
              BASE.plusSeconds(random.nextInt(SPREAD_DAYS * 86_400))));
    }
    return bookings;
  }

  @Override
  public ShadowReport run(long seed, int count, ShadowConfig config) {
    log.debug("run(seed={}, count={})", seed, count);
    String runId = UUID.randomUUID().toString().substring(0, 8);
    String token = vault.tokenise(new Pan("4111 1111 1111 1111"), "12/29").getToken(); // public test card
    Map<String, Integer> byField = new LinkedHashMap<>();
    List<Mismatch> samples = new ArrayList<>();
    int matched = 0;
    int mismatched = 0;
    int errors = 0;
    for (ShadowBooking booking : generate(seed, count)) {
      var result = payments.pay(commandFor(runId, token, booking, config));
      if (result.posting() == null || result.posting().getOperaRef() == null) {
        errors++;
        continue;
      }
      var posting = result.posting();
      OperaClient.Posted posted = opera.find(settings.hotelId(), posting.getFolioId(), posting.getIdempotencyKey()).orElse(null);
      List<Mismatch> differences = compare(booking, posted, posting.getBusinessDate());
      differences.forEach(m -> byField.merge(m.field(), 1, Integer::sum));
      differences.stream().limit(Math.max(0, MAX_SAMPLES - samples.size())).forEach(samples::add);
      if (differences.isEmpty()) {
        matched++;
      } else {
        mismatched++;
      }
    }
    log.info("Shadow run {}: {} bookings, {} match, {} differ ({}, {})", runId, count, matched, mismatched, config.rounding(), config.dateRule());
    return new ShadowReport(runId, config, count, matched, mismatched, byField, samples, errors);
  }

  private static PaymentCommand commandFor(String runId, String token, ShadowBooking booking, ShadowConfig config) {
    Money amount = new Money(Pricing.total(booking, config.rounding()), booking.currency());
    return PaymentCommand.bridge("shadow-" + runId + "-" + booking.ref(), booking.ref(), "F-" + booking.ref(), token, amount,
        Pricing.businessDate(booking, config.dateRule()));
  }

  /**
   * The amount is compared with what OPERA has on the folio. The business date is the one the date rule gave the bridge's own record: OPERA
   * posts every payment on its current business date (D-42), so the rule decides how the bridge books the payment, not how OPERA does.
   */
  private static List<Mismatch> compare(ShadowBooking booking, OperaClient.Posted posted, LocalDate recordedDate) {
    LegacyRules.Expected expected = LegacyRules.expected(booking);
    List<Mismatch> differences = new ArrayList<>();
    Optional<Long> postedMinor = posted == null ? Optional.empty() : posted.amount().map(Money::minor);
    if (postedMinor.filter(minor -> minor == expected.amountMinor()).isEmpty()) {
      String actual = posted == null ? "none" : postedMinor.map(String::valueOf).orElse("unreadable");
      differences.add(new Mismatch(booking.ref(), "amount", String.valueOf(expected.amountMinor()), actual));
    }
    if (posted == null || !recordedDate.equals(expected.businessDate())) {
      String actual = posted == null ? "none" : String.valueOf(recordedDate);
      differences.add(new Mismatch(booking.ref(), "businessDate", String.valueOf(expected.businessDate()), actual));
    }
    return differences;
  }
}
