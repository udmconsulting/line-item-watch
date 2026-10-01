package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReliabilityMaintenanceStore {

    List<ExhaustedSignal> exhaustedSignals(
            TenantId tenantId, PlatformConnectionId connectionId, UUID after, int limit);

    RequeueResult requeueTerminalSignal(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID signalId,
            Instant now);

    enum RequeueResult { REQUEUED, ALREADY_PENDING, INVALID }

    AnchorResult createAndVerifyAnchor(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID lineItemId,
            Instant now);

    Inspection inspect(TenantId tenantId, PlatformConnectionId connectionId);

    RetentionPreview previewRetention(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            RetentionCutoffs cutoffs);

    RetentionResult executeRetention(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            RetentionCutoffs cutoffs,
            boolean confirmed,
            int batchSize);

    boolean acknowledgeFinding(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            UUID findingId,
            Instant now);

    boolean acknowledgeGap(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            Instant now);

    record ExhaustedSignal(UUID signalId, String disposition, String errorCode) {
    }

    record AnchorResult(UUID anchorId, boolean trusted, boolean verified) {
    }

    record Inspection(
            long pendingOperations,
            long activeOperations,
            long failedOperations,
            long openFindings,
            long suspectedGaps,
            long exhaustedSignals) {
    }

    record RetentionCutoffs(
            Instant processedSignalsBefore,
            Instant semanticEventsBefore,
            Instant activityBefore,
            Instant terminalOperationsBefore) {

        public boolean empty() {
            return processedSignalsBefore == null
                    && semanticEventsBefore == null
                    && activityBefore == null
                    && terminalOperationsBefore == null;
        }
    }

    record RetentionPreview(
            long processedSignals,
            long semanticEvents,
            long activityRecords,
            long terminalOperations) {
    }

    record RetentionResult(
            int processedSignals,
            int semanticEvents,
            int activityRecords,
            int terminalOperations) {
    }
}
