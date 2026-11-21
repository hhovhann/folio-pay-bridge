package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.foliopay.TestBase;
import dev.foliopay.domain.model.Money;
import dev.foliopay.gateway.fake.FakeOpera;
import dev.foliopay.service.OverviewService;
import dev.foliopay.service.PaymentService;
import dev.foliopay.service.PaymentService.PaymentCommand;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The dashboard's overview against the real database, and how the dashboard is served and told to sign in. */
@AutoConfigureMockMvc
@DisplayName("Dashboard: the overview against the real database, and how the dashboard is served")
class DashboardIntegrationTest extends TestBase {
  @Autowired private MockMvc mvc;
  @Autowired private OverviewService overview;
  @Autowired private PaymentService payments;

  private void pay(long minor, String currency) {
    payments.pay(PaymentCommand.bridge("k-" + UUID.randomUUID(), "BK-D", "RES-D", token, new Money(minor, currency), null));
  }

  /** Fails if amounts in different currencies are added up, or a queue that needs a person is not counted. */
  @Test
  @DisplayName("The overview keeps each currency apart and counts every queue that needs a person")
  void theOverviewKeepsEachCurrencyApartAndCountsEveryQueueThatNeedsAPerson() {
    pay(80_000, "USD");
    pay(20_000, "USD");
    pay(45_000, "AED");
    opera.arm(FakeOpera.Fault.DOWN);
    pay(10_000, "USD"); // captured, then dead-lettered: OPERA is down

    OverviewService.Overview o = overview.overview();

    assertThat(o.capturedToday()).extracting(OverviewService.Captured::currency, OverviewService.Captured::amountMinor, OverviewService.Captured::count)
        .containsExactly(Tuple.tuple("AED", 45_000L, 1L), Tuple.tuple("USD", 110_000L, 3L));
    assertThat(o.deadLettersReplayable()).isEqualTo(1);
    assertThat(o.deadLettersRejected()).isZero();
    assertThat(o.chargesPending()).isZero();
  }

  @Test
  @DisplayName("The overview leaves shadow runs out")
  void theOverviewLeavesShadowRunsOut() {
    payments.pay(PaymentCommand.bridge("shadow-run-9-BK-S", "BK-S", "RES-S", token, new Money(5_000, "USD"), null));
    assertThat(overview.overview().capturedToday()).isEmpty();
  }

  @Test
  @DisplayName("The dashboard is served at its own path and told it needs no sign-in when the API is open")
  void theDashboardIsServedAtItsOwnPathAndToldItNeedsNoSignInWhenTheApiIsOpen() throws Exception {
    mvc.perform(get("/dashboard")).andExpect(redirectedUrl("/dashboard/"));
    mvc.perform(get("/")).andExpect(status().isOk()); // in the demo the classic console stays the front page
    mvc.perform(get("/api/v1/public/dashboard-config")).andExpect(status().isOk())
        .andExpect(jsonPath("$.auth").value("none")).andExpect(jsonPath("$.demo").value(true));
    mvc.perform(get("/api/v1/overview")).andExpect(status().isOk()).andExpect(jsonPath("$.capturedToday").isArray());
  }
}
