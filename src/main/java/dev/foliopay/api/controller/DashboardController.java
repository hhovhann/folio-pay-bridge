package dev.foliopay.api.controller;

import dev.foliopay.api.ApiPaths;
import dev.foliopay.config.security.DashboardAuth;
import dev.foliopay.service.OverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Dashboard", description = "What the operator dashboard reads: the overview, and how to sign in.")
@RestController
@RequestMapping(ApiPaths.V1)
class DashboardController {
  private final OverviewService overview;
  private final DashboardAuth auth;

  DashboardController(OverviewService overview, DashboardAuth auth) {
    this.overview = overview;
    this.auth = auth;
  }

  @Operation(summary = "Overview", description = "Money captured today per currency (the hotel's day) and every queue that needs a person. Computed by the database.")
  @GetMapping("/overview")
  OverviewService.Overview overview() { return overview.overview(); }

  @Operation(summary = "How the dashboard signs in", description = "Public: none (open API, demo), oidc (authority, client id and scope for authorization code with PKCE), or unavailable (the API needs credentials and no identity provider is configured). Holds no secret.")
  @GetMapping("/public/dashboard-config")
  DashboardAuth.Config dashboardConfig() { return auth.config(); }
}
