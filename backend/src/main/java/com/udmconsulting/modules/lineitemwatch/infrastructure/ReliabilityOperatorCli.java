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
final class ReliabilityOperatorCli implements ApplicationRunner {

    private final ReliabilityOperations operations;
    private final ReliabilityMaintenance maintenance;

    ReliabilityOperatorCli(
            ReliabilityOperations operations, ReliabilityMaintenance maintenance) {
        this.operations = operations;
        this.maintenance = maintenance;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            execute(arguments);
        } catch (ReliabilityOperationException exception) {
            System.out.println("status=FAILED error_code=" + exception.errorCode().name());
        } catch (IllegalArgumentException exception) {
            System.out.println("status=FAILED error_code=INVALID_RECOVERY_OPERATION");
        }
    }

    private void execute(ApplicationArguments args) {
        String command = required(args, "command");
        TenantId tenant = new TenantId(UUID.fromString(required(args, "tenant")));
        PlatformConnectionId connection = new PlatformConnectionId(
                UUID.fromString(required(args, "connection")));
        ActivityContext actor = new ActivityContext(
                new ActivityActor(ActivityActorType.OPERATOR, ActivityActorSource.APPLICATION,
                        required(args, "operator-ref")), UUID.randomUUID());
        switch (command) {
            case "reconcile-tenant" -> queued(operations.reconcileTenant(tenant, connection, actor));
            case "reconcile-deal" -> queued(operations.reconcileDeal(
                    tenant, connection, new ProviderObjectId(required(args, "deal")), actor));
            case "reconcile-line-item" -> queued(operations.reconcileLineItem(
                    tenant, connection, uuid(args, "line-item"), actor));
            case "replay-line-item" -> queued(operations.replayLineItem(
                    tenant, connection, uuid(args, "line-item"), actor));
            case "rebuild-tenant" -> queued(operations.rebuildTenant(tenant, connection, actor));
            case "list-exhausted" -> {
                List<?> rows = maintenance.listExhausted(tenant, connection, null, 1_000);
                System.out.println("status=SUCCEEDED count=" + rows.size());
            }
            case "requeue-signal" -> {
                maintenance.requeue(tenant, connection, uuid(args, "signal"), actor);
                System.out.println("status=SUCCEEDED count=1");
            }
            case "create-anchor" -> {
                var result = maintenance.anchor(
                        tenant, connection, uuid(args, "line-item"));
                System.out.println("status=SUCCEEDED count=1 ref=" + result.anchorId());
            }
            case "inspect" -> {
                var result = maintenance.inspect(tenant, connection);
                System.out.println("status=SUCCEEDED pending=" + result.pendingOperations()
                        + " active=" + result.activeOperations()
                        + " failed=" + result.failedOperations()
                        + " findings=" + result.openFindings()
                        + " gaps=" + result.suspectedGaps()
                        + " exhausted=" + result.exhaustedSignals());
            }
            case "retention-preview" -> {
                var result = maintenance.preview(tenant, connection, cutoffs(args));
                System.out.println("status=SUCCEEDED processed_signals="
                        + result.processedSignals() + " semantic_events="
                        + result.semanticEvents() + " activity=" + result.activityRecords()
                        + " operations=" + result.terminalOperations());
            }
            case "retention-execute" -> {
                boolean confirmed = "EXECUTE".equals(required(args, "confirm"));
                int batch = Integer.parseInt(required(args, "batch-size"));
                var result = maintenance.retain(
                        tenant, connection, cutoffs(args), confirmed, batch, actor);
                System.out.println("status=SUCCEEDED processed_signals="
                        + result.processedSignals() + " semantic_events="
                        + result.semanticEvents() + " activity=" + result.activityRecords()
                        + " operations=" + result.terminalOperations());
            }
            case "acknowledge-finding" -> {
                maintenance.acknowledge(tenant, connection, uuid(args, "finding"), actor);
                System.out.println("status=SUCCEEDED count=1");
            }
            case "acknowledge-gap" -> {
                maintenance.acknowledgeGap(tenant, connection, actor);
                System.out.println("status=SUCCEEDED count=1");
            }
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
}
