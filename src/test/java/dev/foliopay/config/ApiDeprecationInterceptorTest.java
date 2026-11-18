package dev.foliopay.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.foliopay.api.ApiDeprecation;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** An old version kept for existing clients announces its end in the standard headers, so a client's monitoring sees it before it goes (D-39). */
class ApiDeprecationInterceptorTest {
  @RestController
  static class Versions {
    @ApiDeprecation(since = "2027-01-31", sunset = "2027-07-31", successor = "/api/v2/payments")
    @GetMapping("/api/v1/old")
    String old() { return "old"; }

    @GetMapping("/api/v2/payments")
    String current() { return "new"; }
  }

  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Versions()).addInterceptors(new ApiDeprecationInterceptor()).build();

  /** Fails if a deprecated endpoint stops saying so, or says it in a form clients cannot parse (RFC 9745 date, RFC 8594 HTTP date). */
  @Test
  void aDeprecatedEndpointCarriesDeprecationSunsetAndItsSuccessor() throws Exception {
    mvc.perform(get("/api/v1/old"))
        .andExpect(status().isOk())
        .andExpect(header().string("Deprecation", "@1801353600"))
        .andExpect(header().string("Sunset", "Sat, 31 Jul 2027 00:00:00 GMT"))
        .andExpect(header().string("Link", "</api/v2/payments>; rel=\"successor-version\""));
  }

  @Test
  void aCurrentEndpointCarriesNone() throws Exception {
    mvc.perform(get("/api/v2/payments"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Deprecation"))
        .andExpect(header().doesNotExist("Sunset"))
        .andExpect(header().doesNotExist("Link"));
  }
}
