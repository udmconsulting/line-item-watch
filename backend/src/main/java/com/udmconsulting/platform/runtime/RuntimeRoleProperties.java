package com.udmconsulting.platform.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("application.runtime")
public record RuntimeRoleProperties(RuntimeRole role) {

    public RuntimeRoleProperties {
        if (role == null) {
            role = RuntimeRole.LOCAL;
        }
    }
}
