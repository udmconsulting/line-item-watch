package com.udmconsulting.platform.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ProductionConfigurationTest {

    private final List<PropertySource<?>> documents = loadConfiguration();

    @Test
    void healthGroupsExposeOnlySafeLivenessAndDatabaseReadiness() {
        assertThat(value("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(value("management.endpoint.health.show-components")).isEqualTo("never");
        assertThat(value("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState,ping");
        assertThat(value("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db");
    }

    @Test
    void gcpConnectorConfigurationIsConfinedToGcpProfile() {
        PropertySource<?> gcp = profile("gcp");
        assertThat(gcp.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql:///${DATABASE_NAME}");
        assertThat(gcp.getProperty("spring.datasource.hikari.data-source-properties.socketFactory"))
                .isEqualTo("com.google.cloud.sql.postgres.SocketFactory");
        assertThat(documents.get(0).getProperty(
                "spring.datasource.hikari.data-source-properties.socketFactory"))
                .isNull();
    }

    @Test
    void productionProfilesEncodeRoleSafetyContract() {
        PropertySource<?> service = profile("service");
        assertThat(service.getProperty("application.runtime.role")).isEqualTo("SERVICE");
        assertThat(service.getProperty("spring.liquibase.enabled")).isEqualTo(false);

        PropertySource<?> migrate = profile("migrate");
        assertThat(migrate.getProperty("application.runtime.role")).isEqualTo("MIGRATE");
        assertThat(migrate.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(migrate.getProperty("line-item-watch.processing.enabled")).isEqualTo(false);
        assertThat(migrate.getProperty("line-item-watch.reliability.enabled")).isEqualTo(false);

        PropertySource<?> operator = profile("operator");
        assertThat(operator.getProperty("application.runtime.role")).isEqualTo("OPERATOR");
        assertThat(operator.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(operator.getProperty("spring.liquibase.enabled")).isEqualTo(false);
        assertThat(operator.getProperty("line-item-watch.operator.enabled")).isEqualTo(true);
    }

    private Object value(String key) {
        return documents.get(0).getProperty(key);
    }

    private PropertySource<?> profile(String name) {
        return documents.stream()
                .filter(source -> name.equals(source.getProperty(
                        "spring.config.activate.on-profile")))
                .findFirst()
                .orElseThrow();
    }

    private static List<PropertySource<?>> loadConfiguration() {
        try {
            return new YamlPropertySourceLoader().load(
                    "application", new ClassPathResource("application.yml"));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
