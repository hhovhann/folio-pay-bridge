package dev.foliopay.api.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.foliopay.api.dto.ShadowRequest;
import dev.foliopay.domain.exception.NotFoundException;
import dev.foliopay.service.ShadowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("Shadow mode API: refused when the outside systems are real")
class ShadowControllerTest {
  @Test
  @DisplayName("Shadow runs are refused when the systems are external, because they would push synthetic payments through them")
  @SuppressWarnings("unchecked")
  void shadowRunsAreRefusedWhenTheSystemsAreExternalBecauseTheyWouldPushSyntheticPaymentsThroughThem() {
    ObjectProvider<ShadowService> none = mock(ObjectProvider.class);
    when(none.getIfAvailable()).thenReturn(null);

    assertThatThrownBy(() -> new ShadowController(none).run(new ShadowRequest(5, 1L, "compat")))
        .isInstanceOf(NotFoundException.class).hasMessageContaining("not available against external systems");
  }
}
