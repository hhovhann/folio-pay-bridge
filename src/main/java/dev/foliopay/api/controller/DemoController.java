package dev.foliopay.api.controller;

import dev.foliopay.api.ApiPaths;
import dev.foliopay.api.dto.SimRequest;
import dev.foliopay.service.DemoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fault switches, a look inside the vault and the masked log tail. Not registered at all unless demo controls are on (OWASP A05). */
@Tag(name = "Demo", description = "Present only when foliopay.demo-controls=true.")
@RestController
@RequestMapping(ApiPaths.V1)
@ConditionalOnProperty(name = "foliopay.demo-controls", havingValue = "true")
class DemoController {
  private static final int LOG_LINES = 40;
  private final DemoService demo;

  DemoController(DemoService demo) { this.demo = demo; }

  @Operation(summary = "Simulator state")
  @GetMapping("/sim")
  DemoService.SimState state() { return demo.state(); }

  @Operation(summary = "Arm a fault or reset the fakes")
  @PostMapping("/sim")
  DemoService.SimState apply(@Valid @RequestBody SimRequest request) { return demo.apply(request.toCommand()); }

  @Operation(summary = "What the vault stores", description = "Ciphertext previews and display digits only.")
  @GetMapping("/vault")
  List<DemoService.VaultRow> vault() { return demo.vaultRows(); }

  @Operation(summary = "Masked log tail")
  @GetMapping("/logs")
  List<String> logs() { return demo.recentLogs(LOG_LINES); }
}
