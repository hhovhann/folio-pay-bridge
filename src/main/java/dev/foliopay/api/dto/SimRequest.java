package dev.foliopay.api.dto;

import dev.foliopay.service.DemoService;
import jakarta.validation.constraints.Size;

public record SimRequest(@Size(max = 32) String opera, @Size(max = 32) String psp, Boolean reset) {
  public DemoService.SimCommand toCommand() {
    return new DemoService.SimCommand(opera, psp, Boolean.TRUE.equals(reset));
  }
}
