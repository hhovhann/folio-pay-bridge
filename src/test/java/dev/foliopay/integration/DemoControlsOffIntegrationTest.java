package dev.foliopay.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The production default: the vault view, log tail, fault switches and shadow runs do not exist as endpoints. */
@SpringBootTest(properties = {
    "foliopay.opera.hotel-id=DEMO1", "foliopay.opera.payment-method=CC", "foliopay.opera.cashier-id=9001", // the simulated hotel
    "foliopay.keys=local", "foliopay.gateway=fake", "foliopay.demo-controls=false"})
@AutoConfigureMockMvc
@DisplayName("Production default: no demo controls and no API description")
class DemoControlsOffIntegrationTest {
  @Autowired MockMvc mvc;

  @Test
  @DisplayName("Demo endpoints and the API description are not served")
  void demoEndpointsAndTheApiDescriptionAreNotServed() throws Exception {
    mvc.perform(get("/api/v1/vault")).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/logs")).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/sim").contentType(MediaType.APPLICATION_JSON).content("{\"reset\":true}")).andExpect(status().isNotFound());
    mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/shadow").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("The real endpoints still work")
  void theRealEndpointsStillWork() throws Exception {
    mvc.perform(get("/api/v1/charges")).andExpect(status().isOk());
  }

  /** Fails if the demo console is the front page of a real deployment: outside the demo, people land on the operator dashboard. */
  @Test
  @DisplayName("Outside the demo, the front page is the dashboard")
  void outsideTheDemoTheFrontPageIsTheDashboard() throws Exception {
    mvc.perform(get("/")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/dashboard/"));
  }
}

