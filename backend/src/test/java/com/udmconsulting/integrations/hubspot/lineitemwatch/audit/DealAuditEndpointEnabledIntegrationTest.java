package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "line-item-watch.processing.enabled=false",
        "hubspot.ui-extension.enabled=true",
        "hubspot.ui-extension.public-base-uri=https://api.example.test",
        "hubspot.ui-extension.app-id=12345"
})
@Testcontainers
class DealAuditEndpointEnabledIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("hubspot.oauth.client-id", () -> "test-client-id");
        registry.add("hubspot.oauth.client-secret", () -> "test-client-secret");
        registry.add("hubspot.oauth.redirect-uri", () -> "http://localhost:8080/callback");
        registry.add("hubspot.oauth.api-base-url", () -> "http://localhost:9999");
        registry.add("hubspot.oauth.authorization-base-url", () -> "https://app.hubspot.com/oauth/authorize");
        registry.add("hubspot.credentials.key-id", () -> "test-key-1");
        registry.add("hubspot.credentials.encryption-key", () ->
                Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired ApplicationContext applicationContext;

    @Test
    void explicitConfigurationActivatesCompleteSignedReadBoundary() {
        assertThat(applicationContext.containsBean("hubSpotDealAuditController")).isTrue();
        assertThat(applicationContext.containsBean("hubSpotUiExtensionRequestAuthenticator")).isTrue();
        assertThat(applicationContext.containsBean("correlationFilter")).isTrue();
        assertThat(applicationContext.containsBean("httpOperationMetricsFilter")).isTrue();
        assertThat(applicationContext.containsBean("dealAuditErrorHandler")).isTrue();
        assertThat(applicationContext.containsBean("hubSpotDealAuditReadService")).isTrue();
    }
}
