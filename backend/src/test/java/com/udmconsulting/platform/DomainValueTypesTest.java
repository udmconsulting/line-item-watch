package com.udmconsulting.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DomainValueTypesTest {

    @Test
    void tenantIdRequiresAValueAndGeneratesOpaqueIds() {
        assertThatNullPointerException().isThrownBy(() -> new TenantId(null));
        assertThat(TenantId.newId().value()).isNotEqualTo(TenantId.newId().value());
        assertThat(TenantId.newId().value().version()).isEqualTo(4);
    }

    @Test
    void platformConnectionIdRequiresAValueAndGeneratesVersionFourIds() {
        assertThatNullPointerException().isThrownBy(() -> new PlatformConnectionId(null));
        assertThat(PlatformConnectionId.newId().value().version()).isEqualTo(4);
    }

    @Test
    void externalAccountIdRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new ExternalAccountId(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", " 123", "123 ", "\t123", "123\t", "\n123", " 123 "})
    void externalAccountIdRejectsNonCanonicalWhitespace(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ExternalAccountId(value));
    }

    @Test
    void externalAccountIdAcceptsCanonicalStringsAndPreservesCase() {
        assertThat(new ExternalAccountId("123").value()).isEqualTo("123");
        assertThat(new ExternalAccountId("abc-123").value()).isEqualTo("abc-123");
        assertThat(new ExternalAccountId("00A9XYZ").value()).isEqualTo("00A9XYZ");
    }

    @Test
    void ownedClosedSetsExposeOnlyImplementedValues() {
        assertThat(Provider.values()).containsExactly(Provider.HUBSPOT);
        assertThat(ProductModule.values()).containsExactly(ProductModule.LINE_ITEM_WATCH);
    }
}
