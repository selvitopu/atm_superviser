package io.github.selvitopu.atmsup.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code atm.snmp} section: read-only {@code monitor} and read-write {@code operator} users. */
@ConfigurationProperties("atm.snmp")
public record SnmpProperties(UserCredentials monitor, UserCredentials operator) {
}
