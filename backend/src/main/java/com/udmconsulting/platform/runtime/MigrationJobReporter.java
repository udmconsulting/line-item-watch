package com.udmconsulting.platform.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Records a bounded success signal after Liquibase and application startup have completed. */
@Component
@ConditionalOnRuntimeRole(RuntimeRole.MIGRATE)
final class MigrationJobReporter implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationJobReporter.class);

    private final MeterRegistry registry;
    private final Environment environment;

    MigrationJobReporter(MeterRegistry registry, Environment environment) {
        this.registry = registry;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        registry.counter("runtime.job.result", "job", "MIGRATE", "outcome", "SUCCESS")
                .increment();
        LOGGER.atInfo()
                .addKeyValue("component", "runtime")
                .addKeyValue("operation", "migration")
                .addKeyValue("result", "SUCCESS")
                .addKeyValue("environment", environment.getProperty(
                        "APPLICATION_ENVIRONMENT", "unknown"))
                .addKeyValue("releaseRevision", environment.getProperty(
                        "RELEASE_REVISION", "unknown"))
                .log("Database migration job completed");
    }
}
