package dev.foliopay.service.impl;

import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.domain.model.ReconReport;
import dev.foliopay.domain.model.ReconReport.Category;
import dev.foliopay.domain.model.SettlementLine;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.repository.ChargeRepository;
import dev.foliopay.repository.PostingRepository;
import dev.foliopay.service.ReconciliationService;
import dev.foliopay.service.support.SettlementParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
class ReconciliationServiceImpl implements ReconciliationService {
  private static final int MIN_LINES_TO_DISTORT = 3;
  private static final long SHORT_SETTLEMENT_MINOR = 150;
  private static final long UNKNOWN_LINE_MINOR = 7_700;
  /** Shadow-mode runs push synthetic bookings through the real flow; they are not revenue and are kept out of reconciliation. */
  private static final String SHADOW_PREFIX = "shadow-";
  private final ChargeRepository charges;
  private final PostingRepository postings;
  private final PspClient psp;

  ReconciliationServiceImpl(ChargeRepository charges, PostingRepository postings, PspClient psp) {
    this.charges = charges;
    this.postings = postings;
    this.psp = psp;
  }

  @Override
  public ReconReport reconcile(List<SettlementLine> statement) {
    Map<String, List<SettlementLine>> linesByRef = statement.stream().collect(Collectors.groupingBy(SettlementLine::pspRef));
    Set<String> postedChargeIds = postings.findByStatus(Posting.Status.POSTED).stream().map(Posting::getChargeId).collect(Collectors.toSet());
    List<Charge> captured = charges.findByStatus(Charge.Status.CAPTURED).stream().filter(c -> !c.getIdempotencyKey().startsWith(SHADOW_PREFIX)).toList();
    List<ReconReport.Item> items = new ArrayList<>();
    int matched = 0;
    long expected = 0;
    for (Charge charge : captured) {
      expected += charge.getAmountMinor();
      List<SettlementLine> lines = linesByRef.getOrDefault(charge.getPspRef(), List.of());
      Category category = classify(charge, lines, postedChargeIds);
      if (category == null) {
        matched++;
      } else {
        items.add(new ReconReport.Item(category, charge.getPspRef(), charge.getBookingRef(), charge.getAmountMinor(), sum(lines)));
      }
    }
    Set<String> knownRefs = captured.stream().map(Charge::getPspRef).collect(Collectors.toSet());
    Set<String> shadowRefs = charges.findByStatus(Charge.Status.CAPTURED).stream().filter(c -> c.getIdempotencyKey().startsWith(SHADOW_PREFIX)).map(Charge::getPspRef).collect(Collectors.toSet());
    long settled = 0;
    for (Map.Entry<String, List<SettlementLine>> entry : linesByRef.entrySet()) {
      if (shadowRefs.contains(entry.getKey())) {
        continue;
      }
      settled += sum(entry.getValue());
      if (!knownRefs.contains(entry.getKey())) {
        items.add(new ReconReport.Item(Category.UNKNOWN_IN_SETTLEMENT, entry.getKey(), "", 0, sum(entry.getValue())));
      }
    }
    return new ReconReport(matched, countByCategory(items), items, expected, settled);
  }

  @Override
  public ReconReport reconcileCsv(String csv) {
    return reconcile(SettlementParser.parse(csv));
  }

  @Override
  public ReconReport reconcileAcquirerStatement(boolean distorted) {
    List<SettlementLine> statement = new ArrayList<>(psp.settlementStatement());
    if (distorted && statement.size() >= MIN_LINES_TO_DISTORT) {
      statement.remove(0); // one capture missing from the statement
      SettlementLine second = statement.get(0);
      statement.set(0, new SettlementLine(second.pspRef(), second.amountMinor() - SHORT_SETTLEMENT_MINOR, second.currency())); // one settled short
      statement.add(new SettlementLine("cap_unknown1", UNKNOWN_LINE_MINOR, second.currency())); // one we never captured
    }
    return reconcile(statement);
  }

  private static Category classify(Charge charge, List<SettlementLine> lines, Set<String> postedChargeIds) {
    if (lines.isEmpty()) {
      return Category.MISSING_IN_SETTLEMENT;
    }
    if (lines.size() > 1) {
      return Category.DUPLICATE_IN_SETTLEMENT;
    }
    SettlementLine line = lines.get(0);
    if (!line.currency().equals(charge.getCurrency())) {
      return Category.CURRENCY_DIFFERENCE;
    }
    if (line.amountMinor() != charge.getAmountMinor()) {
      return Category.AMOUNT_DIFFERENCE;
    }
    return postedChargeIds.contains(charge.getId()) ? null : Category.NOT_POSTED_TO_PMS;
  }

  private static Map<String, Integer> countByCategory(List<ReconReport.Item> items) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    items.forEach(item -> counts.merge(item.category().name(), 1, Integer::sum));
    return counts;
  }

  private static long sum(List<SettlementLine> lines) {
    return lines.stream().mapToLong(SettlementLine::amountMinor).sum();
  }
}
