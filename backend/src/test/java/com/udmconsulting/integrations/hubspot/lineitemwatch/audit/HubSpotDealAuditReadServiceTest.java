package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.udmconsulting.modules.lineitemwatch.application.DealAuditQuery;
import com.udmconsulting.modules.lineitemwatch.application.DealAuditView;
import com.udmconsulting.modules.lineitemwatch.application.ReadDealAudit;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class HubSpotDealAuditReadServiceTest {

    private final PlatformConnectionService connections = mock(PlatformConnectionService.class);
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final ReadDealAudit useCase = mock(ReadDealAudit.class);
    private final HubSpotDealAuditReadService service =
            new HubSpotDealAuditReadService(connections, entitlements, useCase);

    @Test
    void resolvesOnlySignedAccountThenRechecksActiveConnectionAndEntitlement() {
        PlatformConnection connection = connection(ConnectionStatus.ACTIVE);
        ExternalAccountId signedAccount = connection.externalAccountId();
        when(connections.resolve(Provider.HUBSPOT, signedAccount)).thenReturn(Optional.of(connection));
        when(connections.lockForCommit(connection.tenantId(), connection.id()))
                .thenReturn(Optional.of(connection));
        when(entitlements.lockEnabledForCommit(
                connection.tenantId(), ProductModule.LINE_ITEM_WATCH)).thenReturn(true);
        when(useCase.read(any())).thenReturn(new DealAuditView(
                DealAuditView.Page.empty(), DealAuditView.Page.empty()));
        DealAuditQueryFactory.QueryInput input = new DealAuditQueryFactory.QueryInput(
                new ProviderObjectId("1001"), 10, null, 20, null);

        service.read(new AuthenticatedHubSpotUiCaller(signedAccount), input, UUID.randomUUID());

        verify(useCase).read(new DealAuditQuery(
                connection.tenantId(), connection.id(), input.dealId(),
                10, null, 20, null));
    }

    @Test
    void unknownInactiveAndUnentitledAccountsShareOneEnumerationSafeFailure() {
        ExternalAccountId signedAccount = new ExternalAccountId("456");
        when(connections.resolve(Provider.HUBSPOT, signedAccount)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.read(
                new AuthenticatedHubSpotUiCaller(signedAccount), input(), UUID.randomUUID()))
                .isExactlyInstanceOf(AccountUnavailableException.class);

        PlatformConnection inactive = connection(ConnectionStatus.REAUTH_REQUIRED);
        when(connections.resolve(Provider.HUBSPOT, inactive.externalAccountId()))
                .thenReturn(Optional.of(inactive));
        when(connections.lockForCommit(inactive.tenantId(), inactive.id()))
                .thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.read(
                new AuthenticatedHubSpotUiCaller(inactive.externalAccountId()), input(), UUID.randomUUID()))
                .isExactlyInstanceOf(AccountUnavailableException.class);

        PlatformConnection disconnected = connection(ConnectionStatus.DISCONNECTED);
        when(connections.resolve(Provider.HUBSPOT, disconnected.externalAccountId()))
                .thenReturn(Optional.of(disconnected));
        when(connections.lockForCommit(disconnected.tenantId(), disconnected.id()))
                .thenReturn(Optional.of(disconnected));
        assertThatThrownBy(() -> service.read(
                new AuthenticatedHubSpotUiCaller(disconnected.externalAccountId()),
                input(),
                UUID.randomUUID()))
                .isExactlyInstanceOf(AccountUnavailableException.class);

        PlatformConnection active = connection(ConnectionStatus.ACTIVE);
        when(connections.resolve(Provider.HUBSPOT, active.externalAccountId()))
                .thenReturn(Optional.of(active));
        when(connections.lockForCommit(active.tenantId(), active.id())).thenReturn(Optional.of(active));
        when(entitlements.lockEnabledForCommit(active.tenantId(), ProductModule.LINE_ITEM_WATCH))
                .thenReturn(false);
        assertThatThrownBy(() -> service.read(
                new AuthenticatedHubSpotUiCaller(active.externalAccountId()), input(), UUID.randomUUID()))
                .isExactlyInstanceOf(AccountUnavailableException.class);
    }

    @Test
    void duplicateAccountResolutionIsAnInternalInvariantFailure() {
        ExternalAccountId account = new ExternalAccountId("456");
        when(connections.resolve(Provider.HUBSPOT, account))
                .thenThrow(new IncorrectResultSizeDataAccessException(1, 2));

        assertThatThrownBy(() -> service.read(
                new AuthenticatedHubSpotUiCaller(account), input(), UUID.randomUUID()))
                .isExactlyInstanceOf(AccountResolutionInvariantException.class);
    }

    @Test
    void readsBothSectionsInsideOneReadOnlyRepeatableReadTransaction() throws Exception {
        Transactional transaction = HubSpotDealAuditReadService.class
                .getDeclaredMethod(
                        "read",
                        AuthenticatedHubSpotUiCaller.class,
                        DealAuditQueryFactory.QueryInput.class,
                        UUID.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private static DealAuditQueryFactory.QueryInput input() {
        return new DealAuditQueryFactory.QueryInput(new ProviderObjectId("1001"), 10, null, 20, null);
    }

    private static PlatformConnection connection(ConnectionStatus status) {
        return new PlatformConnection(
                new PlatformConnectionId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                Provider.HUBSPOT,
                new ExternalAccountId(UUID.randomUUID().toString()),
                status);
    }
}
