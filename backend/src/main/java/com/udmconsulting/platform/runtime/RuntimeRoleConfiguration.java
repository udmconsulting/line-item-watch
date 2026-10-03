package com.udmconsulting.platform.runtime;

import java.util.Locale;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails startup when a one-shot or service role is combined with unsafe capabilities. */
@Component
@Lazy(false)
final class RuntimeRoleConfiguration implements SmartInitializingSingleton {

    private final RuntimeRoleProperties properties;
    private final Environment environment;

    RuntimeRoleConfiguration(
            RuntimeRoleProperties properties,
            Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        RuntimeRole role = properties.role();
        boolean web = webApplicationType() != WebApplicationType.NONE;
        boolean processing = environment.getProperty(
                "line-item-watch.processing.enabled", Boolean.class, false);
        boolean reliability = environment.getProperty(
                "line-item-watch.reliability.enabled", Boolean.class, false);
        boolean operator = environment.getProperty(
                "line-item-watch.operator.enabled", Boolean.class, false);
        boolean liquibase = environment.getProperty(
                "spring.liquibase.enabled", Boolean.class, true);

        switch (role) {
            case LOCAL -> {
                // Local development retains explicit property control.
            }
            case SERVICE -> require(
                    web && !liquibase && !operator,
                    "SERVICE requires HTTP, Liquibase disabled, and operator disabled");
            case MIGRATE -> require(
                    !web && liquibase && !processing && !reliability && !operator,
                    "MIGRATE requires no HTTP/workers/operator and Liquibase enabled");
            case OPERATOR -> require(
                    !web && !liquibase && !processing && !reliability && operator,
                    "OPERATOR requires no HTTP/workers/Liquibase and operator enabled");
        }
    }

    private WebApplicationType webApplicationType() {
        String configured = environment.getProperty(
                "spring.main.web-application-type", "servlet");
        return WebApplicationType.valueOf(configured.trim().toUpperCase(Locale.ROOT));
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException("Unsafe runtime role configuration: " + message);
        }
    }
}
