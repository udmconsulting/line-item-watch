package com.udmconsulting.modules.lineitemwatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.modules.lineitemwatch.domain.BillingStart;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.connection.application.PlatformConnectionService;
import com.udmconsulting.platform.connection.application.PlatformConnectionStore;
import com.udmconsulting.platform.connection.domain.ConnectionStatus;
import com.udmconsulting.platform.connection.domain.ExternalAccountId;
import com.udmconsulting.platform.connection.domain.PlatformConnection;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.connection.domain.Provider;
import com.udmconsulting.platform.entitlement.application.EntitlementService;
import com.udmconsulting.platform.entitlement.application.EntitlementStore;
import com.udmconsulting.platform.module.domain.ProductModule;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EstablishLineItemBaselineTest {

    private final TenantId tenantId = TenantId.newId();
    private final PlatformConnectionId connectionId = PlatformConnectionId.newId();
    private final ProviderObjectId dealId = new ProviderObjectId("deal-1");
    private final PlatformConnection connection = new PlatformConnection(
            connectionId,
            tenantId,
            Provider.HUBSPOT,
            new ExternalAccountId("account-1"),
            ConnectionStatus.ACTIVE);
    private final InMemoryConnectionStore connectionStore = new InMemoryConnectionStore();
    private final InMemoryEntitlementStore entitlementStore = new InMemoryEntitlementStore();
    private final StubSource source = new StubSource();
    private final StubSnapshotStore snapshotStore = new StubSnapshotStore();
    private EstablishLineItemBaseline useCase;

    @BeforeEach
    void setUp() {
        connectionStore.connection = connection;
        entitlementStore.enabled = true;
        useCase = new EstablishLineItemBaseline(
                new PlatformConnectionService(connectionStore),
                new EntitlementService(entitlementStore),
                source,
                snapshotStore);
    }

    @Test
    void readsBeforeDelegatingToTransactionalPersistence() {
        LineItemObservation observation = observation(Set.of(dealId));
        source.result = new DealLineItemObservations(dealId, List.of(observation));

        EstablishLineItemBaseline.Result result = useCase.execute(tenantId, connectionId, dealId);

        assertThat(result.observedLineItems()).isEqualTo(1);
        assertThat(result.createdBaselines()).isEqualTo(1);
        assertThat(connectionStore.tenantScopedReads).isEqualTo(1);
        assertThat(entitlementStore.checks).isEqualTo(1);
        assertThat(source.calls).isEqualTo(1);
        assertThat(source.transactionActiveDuringCall).isFalse();
        assertThat(snapshotStore.calls).isEqualTo(1);
    }

    @Test
    void rejectsMissingEntitlementBeforeProviderAccess() {
        entitlementStore.enabled = false;

        assertThatThrownBy(() -> useCase.execute(tenantId, connectionId, dealId))
                .isInstanceOfSatisfying(BaselineSyncException.class,
                        exception -> assertThat(exception.retryable()).isFalse());

        assertThat(source.calls).isZero();
        assertThat(snapshotStore.calls).isZero();
    }

    @Test
    void rejectsInitiallyInactiveConnectionBeforeProviderAccess() {
        connectionStore.connection = new PlatformConnection(
                connectionId,
                tenantId,
                Provider.HUBSPOT,
                new ExternalAccountId("account-1"),
                ConnectionStatus.DISCONNECTED);

        assertThatThrownBy(() -> useCase.execute(tenantId, connectionId, dealId))
                .isInstanceOfSatisfying(BaselineSyncException.class,
                        exception -> assertThat(exception.retryable()).isFalse());

        assertThat(source.calls).isZero();
        assertThat(snapshotStore.calls).isZero();
    }

    @Test
    void zeroLineItemDealIsSuccessfulNoOp() {
        source.result = new DealLineItemObservations(dealId, List.of());
        snapshotStore.result = new LineItemSnapshotStore.PersistenceResult(0, 0, 0, 0, 0);

        EstablishLineItemBaseline.Result result = useCase.execute(tenantId, connectionId, dealId);

        assertThat(result.observedLineItems()).isZero();
        assertThat(result.createdLineItems()).isZero();
        assertThat(result.createdBaselines()).isZero();
        assertThat(result.createdLatestSnapshots()).isZero();
        assertThat(result.updatedLatestSnapshots()).isZero();
        assertThat(result.unchangedLatestSnapshots()).isZero();
        assertThat(snapshotStore.calls).isEqualTo(1);
    }

    @Test
    void inconsistentTargetAssociationCommitsNothing() {
        LineItemObservation observation = observation(Set.of(new ProviderObjectId("deal-2")));
        source.result = new DealLineItemObservations(dealId, List.of(observation));

        assertThatThrownBy(() -> useCase.execute(tenantId, connectionId, dealId))
                .isInstanceOfSatisfying(BaselineSyncException.class,
                        exception -> assertThat(exception.retryable()).isTrue());

        assertThat(snapshotStore.calls).isZero();
    }

    @Test
    void rejectsConnectionOwnedByAnotherTenantBeforeProviderAccess() {
        TenantId otherTenant = TenantId.newId();

        assertThatThrownBy(() -> useCase.execute(otherTenant, connectionId, dealId))
                .isInstanceOfSatisfying(BaselineSyncException.class,
                        exception -> assertThat(exception.retryable()).isFalse());

        assertThat(source.calls).isZero();
        assertThat(snapshotStore.calls).isZero();
    }

    @Test
    void providerFailureCommitsNothing() {
        source.failure = new BaselineSyncException("sanitized provider failure", true);

        assertThatThrownBy(() -> useCase.execute(tenantId, connectionId, dealId))
                .isInstanceOfSatisfying(BaselineSyncException.class,
                        exception -> assertThat(exception.retryable()).isTrue());

        assertThat(snapshotStore.calls).isZero();
    }

    @Test
    void rejectsDuplicateProviderLineItemIdsBeforePersistence() {
        LineItemObservation observation = observation(Set.of(dealId));
        source.result = new DealLineItemObservations(dealId, List.of(observation, observation));

        assertThatThrownBy(() -> useCase.execute(tenantId, connectionId, dealId))
                .isInstanceOfSatisfying(BaselineSyncException.class,
                        exception -> assertThat(exception.retryable()).isFalse());

        assertThat(snapshotStore.calls).isZero();
    }

    private static LineItemObservation observation(Set<ProviderObjectId> dealIds) {
        return new LineItemObservation(
                new ProviderObjectId("line-1"),
                "Consulting",
                null,
                null,
                null,
                null,
                null,
                BillingStart.unspecified(),
                null,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"),
                Instant.parse("2026-09-03T00:00:00Z"),
                dealIds);
    }

    private static final class StubSource implements LineItemBaselineSource {
        private DealLineItemObservations result;
        private int calls;
        private boolean transactionActiveDuringCall;
        private RuntimeException failure;

        @Override
        public DealLineItemObservations readDeal(
            PlatformConnection connection, ProviderObjectId dealId) {
            calls++;
            transactionActiveDuringCall = TransactionSynchronizationManager.isActualTransactionActive();
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static final class StubSnapshotStore implements LineItemSnapshotStore {
        private int calls;
        private PersistenceResult result = new PersistenceResult(1, 1, 1, 0, 0);

        @Override
        public PersistenceResult establish(
                TenantId tenantId,
                PlatformConnectionId connectionId,
                List<LineItemObservation> observations) {
            calls++;
            return result;
        }
    }

    private static final class InMemoryConnectionStore implements PlatformConnectionStore {
        private PlatformConnection connection;
        private int tenantScopedReads;

        @Override
        public PlatformConnection save(PlatformConnection connection) {
            this.connection = connection;
            return connection;
        }

        @Override
        public Optional<PlatformConnection> findByProviderAndExternalAccountId(
                Provider provider, ExternalAccountId externalAccountId) {
            return Optional.ofNullable(connection);
        }

        @Override
        public Optional<PlatformConnection> findByTenantIdAndId(
                TenantId tenantId, PlatformConnectionId connectionId) {
            tenantScopedReads++;
            return Optional.ofNullable(connection)
                    .filter(candidate -> candidate.tenantId().equals(tenantId))
                    .filter(candidate -> candidate.id().equals(connectionId));
        }

        @Override
        public Optional<PlatformConnection> findByTenantIdAndIdForCommit(
                TenantId tenantId, PlatformConnectionId connectionId) {
            return findByTenantIdAndId(tenantId, connectionId);
        }
    }

    private static final class InMemoryEntitlementStore implements EntitlementStore {
        private final Set<String> entries = new HashSet<>();
        private boolean enabled;
        private int checks;

        @Override
        public void enable(TenantId tenantId, ProductModule productModule) {
            entries.add(tenantId + ":" + productModule);
        }

        @Override
        public void disable(TenantId tenantId, ProductModule productModule) {
            entries.remove(tenantId + ":" + productModule);
        }

        @Override
        public boolean isEnabled(TenantId tenantId, ProductModule productModule) {
            checks++;
            return enabled;
        }

        @Override
        public boolean isEnabledForCommit(TenantId tenantId, ProductModule productModule) {
            return isEnabled(tenantId, productModule);
        }
    }
}
