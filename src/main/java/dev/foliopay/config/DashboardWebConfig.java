package dev.foliopay.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The operator dashboard is a single page at /dashboard/ (built from dashboard/ into the jar); it routes inside the page with #/... The
 * classic console at / is a demo tool (it drives the fault switches and the recorded video), so outside the demo / goes to the dashboard.
 */
@Configuration
class DashboardWebConfig implements WebMvcConfigurer {
  private final boolean demoControls;

  DashboardWebConfig(@Value("${foliopay.demo-controls:false}") boolean demoControls) {
    this.demoControls = demoControls;
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController("/dashboard").setViewName("redirect:/dashboard/");
    registry.addViewController("/dashboard/").setViewName("forward:/dashboard/index.html");
    if (!demoControls) {
      registry.addViewController("/").setViewName("redirect:/dashboard/");
    }
  }
}
