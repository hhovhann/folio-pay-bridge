package dev.foliopay.service.impl;

import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.model.SettlementLine;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.gateway.fake.FakeOpera;
import dev.foliopay.gateway.fake.FakePsp;
import dev.foliopay.logging.RecentLogs;
import dev.foliopay.repository.CardTokenRepository;
import dev.foliopay.service.DemoService;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "foliopay.demo-controls", havingValue = "true")
class DemoServiceImpl implements DemoService {
  private static final Logger log = LoggerFactory.getLogger(DemoServiceImpl.class);
  private static final int HEX_PREVIEW = 40;
  /** The vault view shows the newest cards only: a demo page must not read a whole table either. */
  private static final int VAULT_ROWS = 50;
  private static final int MIN_LINES_TO_DISTORT = 3;
  private static final long SHORT_SETTLEMENT_MINOR = 150;
  private static final long UNKNOWN_LINE_MINOR = 7_700;
  private final ObjectProvider<FakePsp> psp;
  private final ObjectProvider<FakeOpera> opera;
  private final RecentLogs logs;
  private final CardTokenRepository tokens;
  private final PspClient processor;

  DemoServiceImpl(ObjectProvider<FakePsp> psp, ObjectProvider<FakeOpera> opera, RecentLogs logs, CardTokenRepository tokens, PspClient processor) {
    this.psp = psp;
    this.opera = opera;
    this.logs = logs;
    this.tokens = tokens;
    this.processor = processor;
  }

  @Override
  public SimState state() {
    log.debug("state()");
    FakePsp fakePsp = psp.getIfAvailable();
    FakeOpera fakeOpera = opera.getIfAvailable();
    if (fakePsp == null || fakeOpera == null) {
      return new SimState("EXTERNAL", "EXTERNAL", 0, 0, 0); // full-flow mode: the processor and OPERA are separate services with their own pages
    }
    return new SimState(
        fakeOpera.armed().name(), fakePsp.armed().name(), fakePsp.captureCount(), fakePsp.capturedTotalMinor(), fakeOpera.postCount());
  }

  @Override
  public SimState apply(SimCommand command) {
    log.debug("apply(opera={}, psp={}, reset={})", command.opera(), command.psp(), command.reset());
    FakePsp fakePsp = psp.getIfAvailable();
    FakeOpera fakeOpera = opera.getIfAvailable();
    if (fakePsp == null || fakeOpera == null) {
      throw new InvalidRequestException("The processor and OPERA are separate services in this mode: use their own pages to arm faults");
    }
    if (command.reset()) {
      fakePsp.reset();
      fakeOpera.reset();
      logs.clear();
    }
    if (command.opera() != null) {
      fakeOpera.arm(parse(FakeOpera.Fault.class, command.opera()));
    }
    if (command.psp() != null) {
      fakePsp.arm(parse(FakePsp.Fault.class, command.psp()));
    }
    return state();
  }

  @Override
  public List<VaultRow> vaultRows() {
    log.debug("vaultRows(limit={})", VAULT_ROWS);
    HexFormat hex = HexFormat.of();
    return tokens.findAll(PageRequest.of(0, VAULT_ROWS, Sort.by("createdAt").descending())).stream()
        .map(t -> new VaultRow(t.getToken(), t.getBin6(), t.getLast4(), preview(hex.formatHex(t.getSealedPan())),
            preview(hex.formatHex(t.getWrappedKey())), t.getKeyId()))
        .toList();
  }

  @Override
  public List<String> recentLogs(int lines) {
    log.debug("recentLogs(lines={})", lines);
    return logs.tail(lines);
  }

  @Override
  public List<SettlementLine> messyAcquirerStatement() {
    log.debug("messyAcquirerStatement()");
    List<SettlementLine> statement = new ArrayList<>(processor.settlementStatement());
    if (statement.size() < MIN_LINES_TO_DISTORT) {
      return statement;
    }
    statement.remove(0);
    SettlementLine second = statement.get(0);
    statement.set(0, new SettlementLine(second.pspRef(), second.amountMinor() - SHORT_SETTLEMENT_MINOR, second.currency()));
    statement.add(new SettlementLine("cap_unknown1", UNKNOWN_LINE_MINOR, second.currency()));
    return statement;
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String name) {
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Unknown fault: " + name.replaceAll("[^A-Za-z_]", "?"));
    }
  }

  private static String preview(String hex) {
    return hex.length() > HEX_PREVIEW ? hex.substring(0, HEX_PREVIEW) + "..." : hex;
  }
}
