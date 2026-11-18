package dev.foliopay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-property OPERA settings. One property here; a chain would key these by hotel id. {@code paymentMethod} is the hotel's payment method
 * code for the card (OPERA's list of values, for example {@code VA} or {@code MC}); {@code cashierId} is the number of the interface cashier
 * OPERA assigns to the bridge's integration user. Both are the hotel's configuration, read from its OPERA, never invented by the bridge.
 */
@ConfigurationProperties("foliopay.opera")
public record OperaProperties(
    @DefaultValue("DEMO1") String hotelId,
    @DefaultValue("1") int folioWindow,
    @DefaultValue("CC") String paymentMethod,
    @DefaultValue("9001") long cashierId) {}
