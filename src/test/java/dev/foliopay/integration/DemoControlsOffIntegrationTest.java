package dev.foliopay.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The production default: the vault view, log tail, fault switches and shadow runs do not exist as endpoints. */
@SpringBootTest(properties = {"foliopay.keys=local", "foliopay.gateway=fake", "foliopay.demo-controls=false"})
@AutoConfigureMockMvc
class DemoControlsOffIntegrationTest {
  @Autowired MockMvc mvc;

  @Test
  void demoEndpointsAndTheApiDescriptionAreNotServed() throws Exception {
    mvc.perform(get("/api/vault")).andExpect(status().isNotFound());
    mvc.perform(get("/api/logs")).andExpect(status().isNotFound());
    mvc.perform(post("/api/sim").contentType(MediaType.APPLICATION_JSON).content("{\"reset\":true}")).andExpect(status().isNotFound());
    mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    mvc.perform(post("/api/shadow").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
  }

  @Test
  void theRealEndpointsStillWork() throws Exception {
    mvc.perform(get("/api/charges")).andExpect(status().isOk());
  }
}
