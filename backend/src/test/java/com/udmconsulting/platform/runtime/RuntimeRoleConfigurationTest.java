package com.udmconsulting.platform.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeRoleConfigurationTest {

    @Test
    void serviceRequiresHttpAndRefusesLiquibaseOrOperator() {
        assertThatCode(() -> configuration(RuntimeRole.SERVICE,
                        "servlet", false, true, true, false)
                .afterSingletonsInstantiated()).doesNotThrowAnyException();

        assertThatThrownBy(() -> configuration(RuntimeRole.SERVICE,
                        "servlet", true, true, true, false)
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void migrateDisablesHttpWorkersAndOperator() {
        assertThatCode(() -> configuration(RuntimeRole.MIGRATE,
                        "none", true, false, false, false)
                .afterSingletonsInstantiated()).doesNotThrowAnyException();

        assertThatThrownBy(() -> configuration(RuntimeRole.MIGRATE,
                        "none", true, true, false, false)
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void operatorRequiresOneShotOnlyConfiguration() {
        assertThatCode(() -> configuration(RuntimeRole.OPERATOR,
                        "none", false, false, false, true)
                .afterSingletonsInstantiated()).doesNotThrowAnyException();

        assertThatThrownBy(() -> configuration(RuntimeRole.OPERATOR,
                        "servlet", false, false, false, true)
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class);
    }

    private static RuntimeRoleConfiguration configuration(
            RuntimeRole role,
            String web,
            boolean liquibase,
            boolean processing,
            boolean reliability,
            boolean operator) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.main.web-application-type", web)
                .withProperty("spring.liquibase.enabled", Boolean.toString(liquibase))
                .withProperty("line-item-watch.processing.enabled", Boolean.toString(processing))
                .withProperty("line-item-watch.reliability.enabled", Boolean.toString(reliability))
                .withProperty("line-item-watch.operator.enabled", Boolean.toString(operator));
        return new RuntimeRoleConfiguration(new RuntimeRoleProperties(role), environment);
    }
}
