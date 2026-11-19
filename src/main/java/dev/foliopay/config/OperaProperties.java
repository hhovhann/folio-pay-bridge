package dev.foliopay.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Per-property OPERA settings. One property here; a chain would key these by hotel id. {@code paymentMethod} is the hotel's payment method
 * code for the card (OPERA's list of values, for example {@code VA} or {@code MC}); {@code cashierId} is the number of the interface cashier
 * OPERA assigns to the bridge's integration user. Both are the hotel's configuration, read from its OPERA, never invented by the bridge: the
 * hotel id, the payment method and the cashier have no default, and a start without one fails (D-17). Window 1 is the guest's own.
 */
@Validated
@ConfigurationProperties("foliopay.opera")
public record OperaProperties(
    @NotBlank String hotelId,
    @DefaultValue("1") int folioWindow,
    @NotBlank String paymentMethod,
    @Positive long cashierId) {}
