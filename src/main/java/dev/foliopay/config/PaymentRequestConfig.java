package dev.foliopay.config;

import dev.foliopay.domain.rule.PaymentRequestPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The hotel's deposit policy, from configuration. Both values are required: a policy that asks guests for money has no default (D-17). */
@Configuration
class PaymentRequestConfig {
  @Bean
  PaymentRequestPolicy paymentRequestPolicy(
      @Value("${foliopay.payment-request.deposit-percent}") int depositPercent,
      @Value("${foliopay.payment-request.deposit-lead-days}") int depositLeadDays) {
    return new PaymentRequestPolicy(depositPercent, depositLeadDays);
  }
}
