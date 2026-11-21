package dev.foliopay.config;

import dev.foliopay.api.ApiPaths;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every API call passes through the deprecation check, so marking an endpoint {@code @ApiDeprecation} is all it takes (D-39), and through
 * the audit trail (D-43).
 */
@Configuration
class ApiVersioningConfig implements WebMvcConfigurer {
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new ApiDeprecationInterceptor()).addPathPatterns(ApiPaths.ALL_VERSIONS);
    registry.addInterceptor(new AuditInterceptor()).addPathPatterns(ApiPaths.ALL_VERSIONS);
  }
}
