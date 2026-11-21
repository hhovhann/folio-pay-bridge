package dev.foliopay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.foliopay.TestBase;
import dev.foliopay.service.NotificationService;
import jakarta.persistence.EntityManagerFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * N+1 and its cousins, measured: a list or a batch that reads one more row with one more query is a cost that grows with the hotel, and
 * review misses it. Each request is sent with few rows and with ten times as many, and the SQL statements it costs must be the same
 * (standards section 14).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
    "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"})
@DisplayName("Performance: no request's queries grow with the rows it reads (no N+1)")
class QueryCountIntegrationTest extends TestBase {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> LISTS =
      List.of("/api/v1/charges", "/api/v1/postings", "/api/v1/links", "/api/v1/notifications", "/api/v1/overview");
  @Autowired MockMvc mvc;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired NotificationService notifications;
  private int bookings;

  /** Bookings made, linked and paid through the API, then announced: every table a list reads gets one more row per payment. */
  private void paidBookings(int count) throws Exception {
    for (int i = 0; i < count; i++) {
      String ref = "B-Q" + bookings++;
      String event = "{\"type\":\"NEW\",\"bookingRef\":\"" + ref + "\",\"folioId\":\"F-" + ref + "\",\"ratePlan\":\"ADVANCE_PURCHASE\","
          + "\"arrival\":\"2026-12-20\",\"totalMinor\":10000,\"currency\":\"USD\",\"today\":\"2026-11-17\"}";
      String outcome = mvc.perform(json(post("/api/v1/booking-events"), event))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      String link = JSON.readTree(outcome).path("links").get(0).path("id").asText();
      mvc.perform(json(post("/api/v1/links/" + link + "/pay"), "{\"token\":\"" + token + "\"}")).andExpect(status().isOk());
    }
    notifications.collect();
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  /** The SQL statements each request costs right now. */
  private Map<String, Long> statements() throws Exception {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    Map<String, Long> cost = new LinkedHashMap<>();
    for (String path : LISTS) {
      statistics.clear();
      mvc.perform(get(path)).andExpect(status().isOk());
      cost.put("GET " + path, statistics.getPrepareStatementCount());
    }
    statistics.clear();
    mvc.perform(json(post("/api/v1/reconcile"), "{}")).andExpect(status().isOk());
    cost.put("POST /api/v1/reconcile", statistics.getPrepareStatementCount());
    return cost;
  }

  /**
   * Fails if a list, the overview or a reconciliation reads its rows one query at a time: a lazy association, a lookup per row in a loop,
   * or an IN list that is no longer sliced. Fails too if the count measures nothing (statistics off), so the check cannot pass empty.
   */
  @Test
  @DisplayName("Every list, the overview and a reconciliation cost the same queries for 3 payments as for 30")
  void everyListTheOverviewAndAReconciliationCostTheSameQueriesFor3PaymentsAsFor30() throws Exception {
    paidBookings(3);
    Map<String, Long> few = statements();
    paidBookings(27);
    Map<String, Long> many = statements();

    assertThat(few.values()).as("the statistics really count queries").allMatch(count -> count > 0);
    assertThat(many).as("statements per request with 30 payments, against 3").isEqualTo(few);
  }
}
