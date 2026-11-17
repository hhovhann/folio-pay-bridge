package dev.foliopay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.foliopay.domain.exception.InvalidRequestException;
import dev.foliopay.domain.model.CardToken;
import dev.foliopay.domain.model.Money;
import dev.foliopay.domain.model.Pan;
import dev.foliopay.gateway.fake.FakeOpera;
import dev.foliopay.gateway.fake.FakePsp;
import dev.foliopay.logging.RecentLogs;
import dev.foliopay.repository.CardTokenRepository;
import dev.foliopay.service.DemoService.SimCommand;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DemoServiceImplTest {
  private final FakePsp psp = new FakePsp();
  private final FakeOpera opera = new FakeOpera(Clock.systemUTC());
  private final RecentLogs logs = new RecentLogs();
  private final CardTokenRepository tokens = mock(CardTokenRepository.class);

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T bean) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(bean);
    return provider;
  }

  private DemoServiceImpl withFakes() { return new DemoServiceImpl(provider(psp), provider(opera), logs, tokens); }

  private DemoServiceImpl withoutFakes() { return new DemoServiceImpl(provider(null), provider(null), logs, tokens); }

  @Test
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
  void aCommandWithNothingInItChangesNothing() {
    assertThat(withFakes().apply(new SimCommand(null, null, false)).opera()).isEqualTo("NONE");
  }

  @Test
  void anUnknownFaultIsRefusedAndTheNameIsSanitisedInTheMessage() {
    assertThatThrownBy(() -> withFakes().apply(new SimCommand("DO\nWN!", null, false)))
        .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Unknown fault").message().doesNotContain("\n").doesNotContain("!");
  }

  @Test
  void whenTheOutsideWorldIsSeparateTheStateSaysSoAndTheFaultsCannotBeArmedHere() {
    DemoServiceImpl service = withoutFakes();
    assertThat(service.state().opera()).isEqualTo("EXTERNAL");
    assertThatThrownBy(() -> service.apply(new SimCommand("DOWN", null, false))).isInstanceOf(InvalidRequestException.class).hasMessageContaining("separate services");
  }

  @Test
  void showsWhatTheVaultStoresAsShortHexPreviewsAndNeverAnyDigitsOfTheNumber() {
    byte[] sealed = new byte[60];
    java.util.Arrays.fill(sealed, (byte) 0xAB);
    CardToken stored = new CardToken("tok_1", sealed, new byte[] {1, 2}, "key-1", new Pan("4111111111111111"), "12/29");
    when(tokens.findAll()).thenReturn(List.of(stored));

    var row = withFakes().vaultRows().get(0);

    assertThat(row.token()).isEqualTo("tok_1");
    assertThat(row.bin6()).isEqualTo("411111");
    assertThat(row.last4()).isEqualTo("1111");
    assertThat(row.sealedPanHex()).endsWith("...").hasSize(43);
    assertThat(row.wrappedKeyHex()).isEqualTo("0102");
    assertThat(row.keyId()).isEqualTo("key-1");
  }

  @Test
  void returnsTheRecentLogLines() {
    logs.add("one");
    logs.add("two");
    assertThat(withFakes().recentLogs(1)).containsExactly("two");
  }
}
