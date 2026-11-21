package dev.foliopay.service.impl;

import dev.foliopay.domain.model.Charge;
import dev.foliopay.domain.model.Posting;
import dev.foliopay.domain.model.ReconReport;
import dev.foliopay.domain.model.ReconReport.Category;
import dev.foliopay.domain.model.ReconReport.Item;
import dev.foliopay.domain.model.ReconReport.Total;
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
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Matches a statement against our captures. The expected side is the captures taken in the statement's period plus any capture the
 * statement names; only those rows (and their postings) are read, never whole tables. Every amount stays in its own currency (D-1): a
 * reference settled in two currencies gives one item per currency, never a sum across them.
 */
@Service
class ReconciliationServiceImpl implements ReconciliationService {
  private static final Logger log = LoggerFactory.getLogger(ReconciliationServiceImpl.class);
  static final int IN_LIST_CHUNK = 1_000;
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
  public ReconReport reconcile(List<SettlementLine> statement, Period period) {
    log.debug("reconcile(lines={}, from={}, to={})", statement.size(), period.from(), period.to());
    Map<String, List<SettlementLine>> linesByRef =
        statement.stream().collect(Collectors.groupingBy(SettlementLine::pspRef, LinkedHashMap::new, Collectors.toList()));
    Map<Boolean, List<Charge>> byShadow = expected(linesByRef.keySet(), period).stream()
        .collect(Collectors.partitioningBy(c -> c.getIdempotencyKey().startsWith(SHADOW_PREFIX)));
    List<Charge> captured = byShadow.get(false);
    Set<String> shadowRefs = byShadow.get(true).stream().map(Charge::getPspRef).collect(Collectors.toSet());
    Set<String> postedChargeIds = inChunks(captured.stream().map(Charge::getId).toList(),
        ids -> postings.findByChargeIdInAndStatus(ids, Posting.Status.POSTED)).stream().map(Posting::getChargeId).collect(Collectors.toSet());

    List<Item> items = new ArrayList<>();
    Map<String, Total> totals = new TreeMap<>();
    int matched = 0;
    for (Charge charge : captured) {
      totals.merge(charge.getCurrency(), new Total(charge.getAmountMinor(), 0), Total::plus);
      List<Item> found = check(charge, linesByRef.getOrDefault(charge.getPspRef(), List.of()), postedChargeIds.contains(charge.getId()));
      if (found.isEmpty()) {
        matched++;
      }
      items.addAll(found);
    }
    Set<String> knownRefs = captured.stream().map(Charge::getPspRef).collect(Collectors.toSet());
    linesByRef.forEach((ref, lines) -> {
      if (shadowRefs.contains(ref)) {
        return;
      }
      lines.forEach(line -> totals.merge(line.currency(), new Total(0, line.amountMinor()), Total::plus));
      if (!knownRefs.contains(ref)) {
        byCurrency(lines).forEach((currency, sum) -> items.add(new Item(Category.UNKNOWN_IN_SETTLEMENT, ref, "", 0, "", sum, currency)));
      }
    });
    log.info("Reconciled {} statement lines against {} captures: {} matched, {} exceptions",
        statement.size(), captured.size(), matched, items.size());
    return new ReconReport(matched, countByCategory(items), items, totals);
  }

  @Override
  public ReconReport reconcileCsv(String csv, Period period) {
    log.debug("reconcileCsv(characters={}, from={}, to={})", csv.length(), period.from(), period.to());
    return reconcile(SettlementParser.parse(csv), period);
  }

  @Override
  public ReconReport reconcileAcquirerStatement(Period period) {
    log.debug("reconcileAcquirerStatement(from={}, to={})", period.from(), period.to());
    return reconcile(psp.settlementStatement(), period);
  }

  /** Captures taken in the period, plus those the statement names (a late settlement of an older capture is matched, not "unknown"). */
  private List<Charge> expected(Set<String> statementRefs, Period period) {
    List<Charge> inPeriod = period.equals(Period.ALL)
        ? charges.findByStatus(Charge.Status.CAPTURED)
        : charges.findByStatusAndBusinessDateBetween(Charge.Status.CAPTURED, period.firstDay(), period.lastDay());
    List<Charge> named = inChunks(List.copyOf(statementRefs), refs -> charges.findByStatusAndPspRefIn(Charge.Status.CAPTURED, refs));
    Map<String, Charge> byId = new LinkedHashMap<>();
    Stream.concat(inPeriod.stream(), named.stream()).forEach(c -> byId.putIfAbsent(c.getId(), c));
    return List.copyOf(byId.values());
  }

  /**
   * What is wrong with one capture, if anything. Lines in another currency are a currency difference, two lines in the right one a
   * duplicate, one line for another amount an amount difference; a clean match that never reached the folio is flagged for the PMS.
   */
  private static List<Item> check(Charge charge, List<SettlementLine> lines, boolean posted) {
    if (lines.isEmpty()) {
      return List.of(item(Category.MISSING_IN_SETTLEMENT, charge, 0, ""));
    }
    List<Item> found = new ArrayList<>();
    Map<String, List<SettlementLine>> groups =
        lines.stream().collect(Collectors.groupingBy(SettlementLine::currency, TreeMap::new, Collectors.toList()));
    groups.forEach((currency, group) -> {
      long settled = group.stream().mapToLong(SettlementLine::amountMinor).sum();
      Category category = !currency.equals(charge.getCurrency()) ? Category.CURRENCY_DIFFERENCE
          : group.size() > 1 ? Category.DUPLICATE_IN_SETTLEMENT
          : settled != charge.getAmountMinor() ? Category.AMOUNT_DIFFERENCE
          : null;
      if (category != null) {
        found.add(item(category, charge, settled, currency));
      }
    });
    if (found.isEmpty() && !posted) {
      found.add(item(Category.NOT_POSTED_TO_PMS, charge, charge.getAmountMinor(), charge.getCurrency()));
    }
    return found;
  }

  private static Item item(Category category, Charge charge, long settledMinor, String settledCurrency) {
    return new Item(
        category, charge.getPspRef(), charge.getBookingRef(), charge.getAmountMinor(), charge.getCurrency(), settledMinor, settledCurrency);
  }

  /**
   * Runs an {@code IN (...)} query in slices: PostgreSQL's driver refuses a statement with more than 65,535 bind parameters, and a
   * statement with tens of thousands is slow to plan anyway. Nothing is queried for an empty list.
   */
  static <T> List<T> inChunks(List<String> values, Function<List<String>, List<T>> query) {
    List<T> all = new ArrayList<>();
    for (int from = 0; from < values.size(); from += IN_LIST_CHUNK) {
      all.addAll(query.apply(values.subList(from, Math.min(values.size(), from + IN_LIST_CHUNK))));
    }
    return all;
  }

  private static Map<String, Long> byCurrency(List<SettlementLine> lines) {
    return lines.stream().collect(Collectors.groupingBy(SettlementLine::currency, TreeMap::new, Collectors.summingLong(SettlementLine::amountMinor)));
  }

  private static Map<String, Integer> countByCategory(List<Item> items) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    items.forEach(item -> counts.merge(item.category().name(), 1, Integer::sum));
    return counts;
  }
}
