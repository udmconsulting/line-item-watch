package com.udmconsulting.modules.lineitemwatch.infrastructure;

import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenance;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityMaintenanceStore.RetentionCutoffs;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperationException;
import com.udmconsulting.modules.lineitemwatch.application.ReliabilityOperations;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import com.udmconsulting.platform.activity.application.ActivityContext;
import com.udmconsulting.platform.activity.domain.ActivityActor;
import com.udmconsulting.platform.activity.domain.ActivityActorSource;
import com.udmconsulting.platform.activity.domain.ActivityActorType;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.credential.application.CredentialKeyRotation;
import com.udmconsulting.platform.runtime.ConditionalOnRuntimeRole;
import com.udmconsulting.platform.runtime.RuntimeRole;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** One-shot internal CLI. Output is intentionally restricted to counts, codes and UUID references. */
@Component
@ConditionalOnProperty(
        prefix = "line-item-watch.operator", name = "enabled", havingValue = "true")
@ConditionalOnRuntimeRole(RuntimeRole.OPERATOR)
final class ReliabilityOperatorCli implements ApplicationRunner {

    private final ReliabilityOperations operations;
    private final ReliabilityMaintenance maintenance;
    private final CredentialKeyRotation keyRotation;

    ReliabilityOperatorCli(
            ReliabilityOperations operations,
            ReliabilityMaintenance maintenance,
            CredentialKeyRotation keyRotation) {
        this.operations = operations;
        this.maintenance = maintenance;
        this.keyRotation = keyRotation;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            execute(arguments);
        } catch (ReliabilityOperationException exception) {
            System.out.println("status=FAILED error_code=" + exception.errorCode().name());
            throw new OperatorCommandException();
        } catch (IllegalArgumentException exception) {
            System.out.println("status=FAILED error_code=INVALID_RECOVERY_OPERATION");
            throw new OperatorCommandException();
        } catch (IllegalStateException exception) {
            System.out.println("status=FAILED error_code=OPERATOR_CONFIGURATION_INVALID");
            throw new OperatorCommandException();
        }
    }

    private void execute(ApplicationArguments args) {
        String command = required(args, "command");
        TenantId tenant = new TenantId(UUID.fromString(required(args, "tenant")));
        ActivityContext actor = new ActivityContext(
                new ActivityActor(ActivityActorType.OPERATOR, ActivityActorSource.APPLICATION,
                        required(args, "operator-ref")), UUID.randomUUID());
        switch (command) {
            case "reconcile-tenant" -> queued(operations.reconcileTenant(
                    tenant, connection(args), actor));
            case "reconcile-deal" -> queued(operations.reconcileDeal(
                    tenant, connection(args), new ProviderObjectId(required(args, "deal")), actor));
            case "reconcile-line-item" -> queued(operations.reconcileLineItem(
                    tenant, connection(args), uuid(args, "line-item"), actor));
            case "replay-line-item" -> queued(operations.replayLineItem(
                    tenant, connection(args), uuid(args, "line-item"), actor));
            case "rebuild-tenant" -> queued(operations.rebuildTenant(
                    tenant, connection(args), actor));
            case "list-exhausted" -> {
                List<?> rows = maintenance.listExhausted(
                        tenant, connection(args), null, 1_000);
                System.out.println("status=SUCCEEDED count=" + rows.size());
            }
            case "requeue-signal" -> {
                maintenance.requeue(
                        tenant, connection(args), uuid(args, "signal"), actor);
                System.out.println("status=SUCCEEDED count=1");
            }
            case "create-anchor" -> {
                var result = maintenance.anchor(
                        tenant, connection(args), uuid(args, "line-item"));
                System.out.println("status=SUCCEEDED count=1 ref=" + result.anchorId());
            }
            case "inspect" -> {
                var result = maintenance.inspect(tenant, connection(args));
                System.out.println("status=SUCCEEDED pending=" + result.pendingOperations()
                        + " active=" + result.activeOperations()
                        + " failed=" + result.failedOperations()
                        + " findings=" + result.openFindings()
                        + " gaps=" + result.suspectedGaps()
                        + " exhausted=" + result.exhaustedSignals());
            }
            case "retention-preview" -> {
                var result = maintenance.preview(tenant, connection(args), cutoffs(args));
                System.out.println("status=SUCCEEDED processed_signals="
                        + result.processedSignals() + " semantic_events="
                        + result.semanticEvents() + " activity=" + result.activityRecords()
                        + " operations=" + result.terminalOperations());
            }
            case "retention-execute" -> {
                boolean confirmed = "EXECUTE".equals(required(args, "confirm"));
                int batch = Integer.parseInt(required(args, "batch-size"));
                var result = maintenance.retain(
                        tenant, connection(args), cutoffs(args), confirmed, batch, actor);
                System.out.println("status=SUCCEEDED processed_signals="
                        + result.processedSignals() + " semantic_events="
                        + result.semanticEvents() + " activity=" + result.activityRecords()
                        + " operations=" + result.terminalOperations());
            }
            case "acknowledge-finding" -> {
                maintenance.acknowledge(
                        tenant, connection(args), uuid(args, "finding"), actor);
                System.out.println("status=SUCCEEDED count=1");
            }
            case "acknowledge-gap" -> {
                maintenance.acknowledgeGap(tenant, connection(args), actor);
                System.out.println("status=SUCCEEDED count=1");
            }
            case "credential-key-rewrap" -> {
                int batch = Integer.parseInt(required(args, "batch-size"));
                var result = keyRotation.rewrap(tenant, batch, actor);
                System.out.println("status=SUCCEEDED rewrapped=" + result.rewrapped()
                        + " concurrent_changes=" + result.concurrentChanges()
                        + " remaining=" + result.remaining());
            }
            case "credential-key-verify" -> System.out.println(
                    "status=SUCCEEDED remaining=" + keyRotation.remaining(tenant));
            default -> throw new IllegalArgumentException("unsupported command");
        }
    }

    private static RetentionCutoffs cutoffs(ApplicationArguments args) {
        return new RetentionCutoffs(
                optionalInstant(args, "processed-signals-before"),
                optionalInstant(args, "semantic-events-before"),
                optionalInstant(args, "activity-before"),
                optionalInstant(args, "terminal-operations-before"));
    }

    private static Instant optionalInstant(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? null : Instant.parse(values.getFirst());
    }

    private static UUID uuid(ApplicationArguments args, String name) {
        return UUID.fromString(required(args, name));
    }

    private static PlatformConnectionId connection(ApplicationArguments args) {
        return new PlatformConnectionId(UUID.fromString(required(args, "connection")));
    }

    private static String required(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("invalid argument");
        }
        return values.getFirst();
    }

    private static void queued(UUID operationId) {
        System.out.println("status=PENDING count=1 ref=" + operationId);
    }

    private static final class OperatorCommandException extends RuntimeException {

        private OperatorCommandException() {
            super("Operator command failed");
        }
    }
}
