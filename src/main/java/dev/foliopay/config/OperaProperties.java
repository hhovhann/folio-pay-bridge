package dev.foliopay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Per-property OPERA settings. One property here; a chain would key these by hotel id. */
@ConfigurationProperties("foliopay.opera")
public record OperaProperties(
    @DefaultValue("DEMO1") String hotelId,
    @DefaultValue("1") int folioWindow,
    @DefaultValue("CC") String paymentCode,
    @DefaultValue("FPBRIDGE") String cashierId) {}
