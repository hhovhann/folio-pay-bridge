package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.model.CardToken;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.domain.model.SettlementLine;
import dev.foliopay.gateway.PspClient;
import dev.foliopay.gateway.fake.FakeOpera;
import dev.foliopay.gateway.fake.FakePsp;
import dev.foliopay.logging.RecentLogs;
import dev.foliopay.repository.CardTokenRepository;
import dev.foliopay.service.DemoService.SimCommand;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@DisplayName("Demo controls: the fakes' state, faults and the vault view")
class DemoServiceImplTest {
  private final FakePsp psp = new FakePsp();
  private final FakeOpera opera = new FakeOpera(Clock.systemUTC());
  private final RecentLogs logs = new RecentLogs();
  private final CardTokenRepository tokens = mock(CardTokenRepository.class);
  private final PspClient processor = mock(PspClient.class);

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T bean) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(bean);
    return provider;
  }

  private DemoServiceImpl withFakes() { return new DemoServiceImpl(provider(psp), provider(opera), logs, tokens, processor); }

  private DemoServiceImpl withoutFakes() { return new DemoServiceImpl(provider(null), provider(null), logs, tokens, processor); }

  /** Moved from ReconciliationServiceImplTest in phase 21, with the distortion it checks: a demo value has no place in production code. */
  @Test
  @DisplayName("The messy statement drops one capture, settles one short and adds one we never captured")
  void theMessyStatementDropsOneCaptureSettlesOneShortAndAddsOneWeNeverCaptured() {
    when(processor.settlementStatement()).thenReturn(List.of(
        new SettlementLine("cap_a", 1_000, "USD"), new SettlementLine("cap_b", 2_000, "USD"), new SettlementLine("cap_c", 3_000, "USD")));

    List<SettlementLine> messy = withFakes().messyAcquirerStatement();

    assertThat(messy).extracting(SettlementLine::pspRef).containsExactly("cap_b", "cap_c", "cap_unknown1");
    assertThat(messy.get(0).amountMinor()).as("settled short").isLessThan(2_000);
  }

  @Test
  @DisplayName("A statement too short to distort is returned as it is")
  void aStatementTooShortToDistortIsReturnedAsItIs() {
    List<SettlementLine> two = List.of(new SettlementLine("cap_a", 1_000, "USD"), new SettlementLine("cap_b", 2_000, "USD"));
    when(processor.settlementStatement()).thenReturn(two);

    assertThat(withFakes().messyAcquirerStatement()).isEqualTo(two);
  }

  @Test
  @DisplayName("Reports the state of the in-memory fakes")
  void reportsTheStateOfTheInMemoryFakes() {
    psp.capture("k:cap", "auth", new Money(2_500, "USD"));
    var state = withFakes().state();
    assertThat(state.psp()).isEqualTo("NONE");
    assertThat(state.opera()).isEqualTo("NONE");
    assertThat(state.pspCaptures()).isEqualTo(1);
    assertThat(state.pspCapturedMinor()).isEqualTo(2_500);
    assertThat(state.operaPosts()).isZero();
  }

  @Test
  @DisplayName("Arms faults and resets everything")
  void armsFaultsAndResetsEverything() {
    DemoServiceImpl service = withFakes();
    var armed = service.apply(new SimCommand("DOWN", "DECLINE_NEXT", false));
    assertThat(armed.opera()).isEqualTo("DOWN");
    assertThat(armed.psp()).isEqualTo("DECLINE_NEXT");

    logs.add("a line");
    var reset = service.apply(new SimCommand(null, null, true));
    assertThat(reset.opera()).isEqualTo("NONE");
    assertThat(reset.psp()).isEqualTo("NONE");
    assertThat(logs.tail(10)).isEmpty();
  }

  @Test
  @DisplayName("A command with nothing in it changes nothing")
  void aCommandWithNothingInItChangesNothing() {
    assertThat(withFakes().apply(new SimCommand(null, null, false)).opera()).isEqualTo("NONE");
  }

  @Test
  @DisplayName("An unknown fault is refused, and its name is sanitised in the message")
  void anUnknownFaultIsRefusedAndTheNameIsSanitisedInTheMessage() {
    assertThatThrownBy(() -> withFakes().apply(new SimCommand("DO\nWN!", null, false)))
        .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Unknown fault").message().doesNotContain("\n").doesNotContain("!");
  }

  @Test
  @DisplayName("When the outside world is separate, the state says so and the faults cannot be armed here")
  void whenTheOutsideWorldIsSeparateTheStateSaysSoAndTheFaultsCannotBeArmedHere() {
    DemoServiceImpl service = withoutFakes();
    assertThat(service.state().opera()).isEqualTo("EXTERNAL");
    assertThatThrownBy(() -> service.apply(new SimCommand("DOWN", null, false))).isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("separate services");
  }

  @Test
  @DisplayName("Shows what the vault stores as short hex previews, never any digit of the number")
  void showsWhatTheVaultStoresAsShortHexPreviewsAndNeverAnyDigitsOfTheNumber() {
    byte[] sealed = new byte[60];
    Arrays.fill(sealed, (byte) 0xAB);
    CardToken stored = new CardToken("tok_1", sealed, new byte[] {1, 2}, "key-1", new Pan("4111111111111111"), "12/29", Instant.EPOCH);
    when(tokens.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(stored)));

    var row = withFakes().vaultRows().get(0);

    assertThat(row.token()).isEqualTo("tok_1");
    assertThat(row.bin6()).isEqualTo("411111");
    assertThat(row.last4()).isEqualTo("1111");
    assertThat(row.sealedPanHex()).endsWith("...").hasSize(43);
    assertThat(row.wrappedKeyHex()).isEqualTo("0102");
    assertThat(row.keyId()).isEqualTo("key-1");
  }

  @Test
  @DisplayName("Returns the recent log lines")
  void returnsTheRecentLogLines() {
    logs.add("one");
    logs.add("two");
    assertThat(withFakes().recentLogs(1)).containsExactly("two");
  }
}
