package dev.foliopay.service.impl;

import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.gateway.fake.FakeOpera;
import dev.foliopay.gateway.fake.FakePsp;
import dev.foliopay.logging.RecentLogs;
import dev.foliopay.repository.CardTokenRepository;
import dev.foliopay.service.DemoService;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "foliopay.demo-controls", havingValue = "true")
class DemoServiceImpl implements DemoService {
  private static final int HEX_PREVIEW = 40;
  private final ObjectProvider<FakePsp> psp;
  private final ObjectProvider<FakeOpera> opera;
  private final RecentLogs logs;
  private final CardTokenRepository tokens;

  DemoServiceImpl(ObjectProvider<FakePsp> psp, ObjectProvider<FakeOpera> opera, RecentLogs logs, CardTokenRepository tokens) {
    this.psp = psp;
    this.opera = opera;
    this.logs = logs;
    this.tokens = tokens;
  }

  @Override
  public SimState state() {
    FakePsp fakePsp = psp.getIfAvailable();
    FakeOpera fakeOpera = opera.getIfAvailable();
    if (fakePsp == null || fakeOpera == null) {
      return new SimState("EXTERNAL", "EXTERNAL", 0, 0, 0); // full-flow mode: the processor and OPERA are separate services with their own pages
    }
    return new SimState(fakeOpera.armed().name(), fakePsp.armed().name(), fakePsp.captureCount(), fakePsp.capturedTotalMinor(), fakeOpera.postCount());
  }

  @Override
  public SimState apply(SimCommand command) {
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
    HexFormat hex = HexFormat.of();
    return tokens.findAll().stream()
        .map(t -> new VaultRow(t.getToken(), t.getBin6(), t.getLast4(), preview(hex.formatHex(t.getSealedPan())), preview(hex.formatHex(t.getWrappedKey())), t.getKeyId()))
        .toList();
  }

  @Override
  public List<String> recentLogs(int lines) {
    return logs.tail(lines);
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
