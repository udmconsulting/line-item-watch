--liquibase formatted sql

--changeset udmconsulting:009-01-create-reliability-state
CREATE TABLE line_item_watch_reliability_state (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    ingestion_state VARCHAR(16) NOT NULL,
    coverage_state VARCHAR(32) NOT NULL,
    possible_gap_since TIMESTAMP WITH TIME ZONE,
    last_signal_observed_at TIMESTAMP WITH TIME ZONE,
    last_successfully_processed_at TIMESTAMP WITH TIME ZONE,
    last_reconciled_at TIMESTAMP WITH TIME ZONE,
    reconciliation_outcome VARCHAR(32) NOT NULL DEFAULT 'NOT_RUN',
    gap_acknowledged_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_line_item_watch_reliability_state PRIMARY KEY (tenant_id, connection_id),
    CONSTRAINT fk_line_item_watch_reliability_state_connection
        FOREIGN KEY (tenant_id, connection_id)
        REFERENCES platform_connection (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_reliability_ingestion
        CHECK (ingestion_state IN ('OBSERVING', 'PAUSED')),
    CONSTRAINT chk_line_item_watch_reliability_coverage
        CHECK (coverage_state IN ('NO_KNOWN_GAP', 'POSSIBLE_GAP')),
    CONSTRAINT chk_line_item_watch_reliability_gap
        CHECK (
            (coverage_state = 'POSSIBLE_GAP') = (possible_gap_since IS NOT NULL)
            AND (gap_acknowledged_at IS NULL OR (
                coverage_state = 'POSSIBLE_GAP'
                AND gap_acknowledged_at >= possible_gap_since
            ))
        ),
    CONSTRAINT chk_line_item_watch_reliability_reconciliation
        CHECK (reconciliation_outcome IN (
            'NOT_RUN', 'SUCCEEDED', 'DRIFT_REPAIRED', 'UNAVAILABLE', 'CONFLICT'
        ))
);

INSERT INTO line_item_watch_reliability_state (
    tenant_id, connection_id, ingestion_state, coverage_state, possible_gap_since
)
SELECT DISTINCT
    connection.tenant_id,
    connection.id,
    CASE WHEN connection.status = 'ACTIVE' AND entitlement.tenant_id IS NOT NULL
        THEN 'OBSERVING' ELSE 'PAUSED' END,
    CASE WHEN connection.status = 'ACTIVE' AND entitlement.tenant_id IS NOT NULL
        THEN 'NO_KNOWN_GAP' ELSE 'POSSIBLE_GAP' END,
    CASE WHEN connection.status = 'ACTIVE' AND entitlement.tenant_id IS NOT NULL
        THEN NULL ELSE connection.status_changed_at END
FROM platform_connection connection
LEFT JOIN tenant_entitlement entitlement
  ON entitlement.tenant_id = connection.tenant_id
 AND entitlement.product_module = 'LINE_ITEM_WATCH'
WHERE entitlement.tenant_id IS NOT NULL
   OR EXISTS (
       SELECT 1 FROM line_item_watch_line_item item
       WHERE item.tenant_id = connection.tenant_id
         AND item.connection_id = connection.id
   );
--rollback DROP TABLE line_item_watch_reliability_state;

--changeset udmconsulting:009-02-create-observation-period
CREATE TABLE line_item_watch_observation_period (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    start_reason VARCHAR(32) NOT NULL,
    end_reason VARCHAR(32),
    CONSTRAINT pk_line_item_watch_observation_period PRIMARY KEY (id),
    CONSTRAINT uq_line_item_watch_observation_period_owner
        UNIQUE (tenant_id, connection_id, id),
    CONSTRAINT fk_line_item_watch_observation_period_connection
        FOREIGN KEY (tenant_id, connection_id)
        REFERENCES platform_connection (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_observation_period_start
        CHECK (start_reason IN ('MIGRATED_ACTIVE', 'ACTIVATED', 'REAUTHORIZED', 'ENTITLEMENT_REACTIVATED')),
    CONSTRAINT chk_line_item_watch_observation_period_end
        CHECK (
            (ended_at IS NULL AND end_reason IS NULL)
            OR (ended_at >= started_at AND end_reason IN (
                'REAUTH_REQUIRED', 'DISCONNECTED', 'ENTITLEMENT_DEACTIVATED'
            ))
        )
);

CREATE UNIQUE INDEX uq_line_item_watch_observation_period_active
    ON line_item_watch_observation_period (tenant_id, connection_id)
    WHERE ended_at IS NULL;

CREATE INDEX idx_line_item_watch_observation_period_time
    ON line_item_watch_observation_period (tenant_id, connection_id, started_at, id);

INSERT INTO line_item_watch_observation_period (
    id, tenant_id, connection_id, started_at, start_reason
)
SELECT gen_random_uuid(), state.tenant_id, state.connection_id,
       GREATEST(connection.status_changed_at, entitlement.enabled_at),
       'MIGRATED_ACTIVE'
FROM line_item_watch_reliability_state state
JOIN platform_connection connection
  ON connection.tenant_id = state.tenant_id AND connection.id = state.connection_id
JOIN tenant_entitlement entitlement
  ON entitlement.tenant_id = state.tenant_id
 AND entitlement.product_module = 'LINE_ITEM_WATCH'
WHERE state.ingestion_state = 'OBSERVING';
--rollback DROP TABLE line_item_watch_observation_period;

--changeset udmconsulting:009-03-create-line-item-reliability
CREATE TABLE line_item_watch_line_item_reliability (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    line_item_id UUID NOT NULL,
    provider_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    reconciliation_outcome VARCHAR(32) NOT NULL DEFAULT 'NOT_RUN',
    last_reconciled_at TIMESTAMP WITH TIME ZONE,
    possible_gap_since TIMESTAMP WITH TIME ZONE,
    retained_from TIMESTAMP WITH TIME ZONE NOT NULL,
    retention_limited BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_line_item_watch_line_item_reliability PRIMARY KEY (line_item_id),
    CONSTRAINT uq_line_item_watch_line_item_reliability_owner
        UNIQUE (tenant_id, connection_id, line_item_id),
    CONSTRAINT fk_line_item_watch_line_item_reliability_item
        FOREIGN KEY (tenant_id, connection_id, line_item_id)
        REFERENCES line_item_watch_line_item (tenant_id, connection_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_line_item_reliability_provider
        CHECK (provider_state IN ('UNKNOWN', 'PRESENT', 'ABSENT', 'INACCESSIBLE')),
    CONSTRAINT chk_line_item_watch_line_item_reliability_outcome
        CHECK (reconciliation_outcome IN (
            'NOT_RUN', 'SUCCEEDED', 'DRIFT_REPAIRED', 'UNAVAILABLE', 'CONFLICT'
        ))
);

INSERT INTO line_item_watch_line_item_reliability (
    tenant_id, connection_id, line_item_id, retained_from
)
SELECT tenant_id, connection_id, line_item_id, history_observed_from
FROM line_item_watch_snapshot
WHERE snapshot_kind = 'LATEST';

CREATE INDEX idx_line_item_watch_line_item_reliability_reconciliation
    ON line_item_watch_line_item_reliability (
        tenant_id, connection_id, last_reconciled_at, line_item_id
    );
--rollback DROP TABLE line_item_watch_line_item_reliability;

--changeset udmconsulting:009-04-create-reliability-operation-and-finding
CREATE TABLE line_item_watch_reliability_operation (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    line_item_id UUID,
    external_deal_id VARCHAR(255),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token UUID,
    claimed_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    cursor_line_item_id UUID,
    cursor_external_deal_id VARCHAR(255),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64),
    CONSTRAINT pk_line_item_watch_reliability_operation PRIMARY KEY (id),
    CONSTRAINT uq_line_item_watch_reliability_operation_owner
        UNIQUE (tenant_id, connection_id, id),
    CONSTRAINT fk_line_item_watch_reliability_operation_connection
        FOREIGN KEY (tenant_id, connection_id)
        REFERENCES platform_connection (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_line_item_watch_reliability_operation_line_item
        FOREIGN KEY (tenant_id, connection_id, line_item_id)
        REFERENCES line_item_watch_line_item (tenant_id, connection_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_reliability_operation_type
        CHECK (operation_type IN (
            'RECONCILE', 'REPLAY', 'REBUILD', 'RETENTION', 'REQUEUE'
        )),
    CONSTRAINT chk_line_item_watch_reliability_operation_scope
        CHECK (
            (scope_type = 'TENANT' AND line_item_id IS NULL AND external_deal_id IS NULL)
            OR (scope_type = 'DEAL' AND line_item_id IS NULL
                AND external_deal_id IS NOT NULL AND btrim(external_deal_id) <> '')
            OR (scope_type = 'LINE_ITEM' AND line_item_id IS NOT NULL
                AND external_deal_id IS NULL)
            OR (scope_type = 'SIGNAL' AND line_item_id IS NOT NULL
                AND external_deal_id IS NULL)
        ),
    CONSTRAINT chk_line_item_watch_reliability_operation_status
        CHECK (status IN ('PENDING', 'CLAIMED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_line_item_watch_reliability_operation_attempts
        CHECK (attempt_count >= 0),
    CONSTRAINT chk_line_item_watch_reliability_operation_claim
        CHECK (
            (status = 'CLAIMED' AND claim_token IS NOT NULL
                AND claimed_at IS NOT NULL AND lease_until > claimed_at
                AND completed_at IS NULL)
            OR (status = 'PENDING' AND claim_token IS NULL
                AND claimed_at IS NULL AND lease_until IS NULL
                AND completed_at IS NULL)
            OR (status IN ('SUCCEEDED', 'FAILED') AND claim_token IS NULL
                AND claimed_at IS NULL AND lease_until IS NULL
                AND completed_at IS NOT NULL)
        ),
    CONSTRAINT chk_line_item_watch_reliability_operation_error
        CHECK (last_error_code IS NULL OR (
            last_error_code = upper(last_error_code)
            AND btrim(last_error_code) <> '' AND char_length(last_error_code) <= 64
        ))
);

CREATE INDEX idx_line_item_watch_reliability_operation_due
    ON line_item_watch_reliability_operation (next_attempt_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_line_item_watch_reliability_operation_expired
    ON line_item_watch_reliability_operation (lease_until, id)
    WHERE status = 'CLAIMED';

CREATE TABLE line_item_watch_reconciliation_finding (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    line_item_id UUID,
    finding_type VARCHAR(48) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_line_item_watch_reconciliation_finding PRIMARY KEY (id),
    CONSTRAINT fk_line_item_watch_reconciliation_finding_operation
        FOREIGN KEY (tenant_id, connection_id, operation_id)
        REFERENCES line_item_watch_reliability_operation (tenant_id, connection_id, id)
        ON DELETE CASCADE,
    CONSTRAINT fk_line_item_watch_reconciliation_finding_line_item
        FOREIGN KEY (tenant_id, connection_id, line_item_id)
        REFERENCES line_item_watch_line_item (tenant_id, connection_id, id)
        ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_reconciliation_finding_type
        CHECK (finding_type IN (
            'PROPERTY_DRIFT', 'ASSOCIATION_DRIFT', 'PROVIDER_OBJECT_ABSENT',
            'PROVIDER_OBJECT_INACCESSIBLE', 'LOCALLY_UNKNOWN_PROVIDER_OBJECT',
            'PROVIDER_STATE_CONFLICT'
        )),
    CONSTRAINT chk_line_item_watch_reconciliation_finding_status
        CHECK (
            (status = 'OPEN' AND resolved_at IS NULL)
            OR (status = 'ACKNOWLEDGED' AND resolved_at IS NOT NULL)
        )
);

CREATE INDEX idx_line_item_watch_reconciliation_finding_open
    ON line_item_watch_reconciliation_finding (tenant_id, connection_id, detected_at, id)
    WHERE status = 'OPEN';
--rollback DROP TABLE line_item_watch_reconciliation_finding; DROP TABLE line_item_watch_reliability_operation;

--changeset udmconsulting:009-05-create-replay-anchor
CREATE TABLE line_item_watch_replay_anchor (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    line_item_id UUID NOT NULL,
    anchor_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL,
    name TEXT,
    quantity NUMERIC(38, 18),
    unit_price NUMERIC(38, 18),
    unit_discount NUMERIC(38, 18),
    discount_percentage NUMERIC(20, 10),
    billing_frequency VARCHAR(64),
    billing_start_date DATE,
    billing_start_delay_unit VARCHAR(8),
    billing_start_delay_count INTEGER,
    recurring_billing_period VARCHAR(64),
    known_properties TEXT[] NOT NULL,
    deal_set_complete BOOLEAN NOT NULL,
    provider_created_at TIMESTAMP WITH TIME ZONE,
    provider_updated_at TIMESTAMP WITH TIME ZONE,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE,
    history_coverage_mode VARCHAR(32) NOT NULL,
    history_observed_from TIMESTAMP WITH TIME ZONE NOT NULL,
    evidence_through_occurred_at TIMESTAMP WITH TIME ZONE,
    evidence_through_deduplication_key BYTEA,
    evidence_through_signal_id UUID,
    trusted BOOLEAN NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_line_item_watch_replay_anchor PRIMARY KEY (id),
    CONSTRAINT uq_line_item_watch_replay_anchor_owner
        UNIQUE (tenant_id, connection_id, id),
    CONSTRAINT uq_line_item_watch_replay_anchor_time
        UNIQUE (line_item_id, anchor_at),
    CONSTRAINT fk_line_item_watch_replay_anchor_line_item
        FOREIGN KEY (tenant_id, connection_id, line_item_id)
        REFERENCES line_item_watch_line_item (tenant_id, connection_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_replay_anchor_lifecycle
        CHECK (
            (lifecycle_state = 'PRESENT' AND deleted_at IS NULL)
            OR (lifecycle_state = 'DELETED' AND deleted_at IS NOT NULL)
        ),
    CONSTRAINT chk_line_item_watch_replay_anchor_coverage
        CHECK (history_coverage_mode IN ('BASELINE_ANCHORED', 'SIGNAL_FIRST')),
    CONSTRAINT chk_line_item_watch_replay_anchor_watermark
        CHECK (
            (evidence_through_occurred_at IS NULL
                AND evidence_through_deduplication_key IS NULL
                AND evidence_through_signal_id IS NULL)
            OR (evidence_through_occurred_at IS NOT NULL
                AND octet_length(evidence_through_deduplication_key) = 32
                AND evidence_through_signal_id IS NOT NULL)
        ),
    CONSTRAINT chk_line_item_watch_replay_anchor_trust
        CHECK ((trusted AND verified_at IS NOT NULL) OR (NOT trusted AND verified_at IS NULL))
);

CREATE TABLE line_item_watch_replay_anchor_deal (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    anchor_id UUID NOT NULL,
    external_deal_id VARCHAR(255) NOT NULL,
    CONSTRAINT pk_line_item_watch_replay_anchor_deal PRIMARY KEY (anchor_id, external_deal_id),
    CONSTRAINT fk_line_item_watch_replay_anchor_deal_anchor
        FOREIGN KEY (tenant_id, connection_id, anchor_id)
        REFERENCES line_item_watch_replay_anchor (tenant_id, connection_id, id)
        ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_replay_anchor_deal_id
        CHECK (btrim(external_deal_id) <> '' AND external_deal_id = btrim(external_deal_id))
);

CREATE INDEX idx_line_item_watch_replay_anchor_latest
    ON line_item_watch_replay_anchor (
        tenant_id, connection_id, line_item_id, anchor_at DESC, id DESC
    );
--rollback DROP TABLE line_item_watch_replay_anchor_deal; DROP TABLE line_item_watch_replay_anchor;

--changeset udmconsulting:009-06-add-processing-failure-disposition
ALTER TABLE line_item_watch_signal_processing
    ADD COLUMN failure_disposition VARCHAR(32);

UPDATE line_item_watch_signal_processing
SET failure_disposition = CASE
    WHEN last_error_code = 'RETRY_EXHAUSTED' THEN 'RETRY_EXHAUSTED'
    ELSE 'PERMANENT'
END
WHERE status = 'FAILED';

ALTER TABLE line_item_watch_signal_processing
    ADD CONSTRAINT chk_line_item_watch_signal_processing_disposition
        CHECK (
            (status = 'FAILED' AND failure_disposition IN ('PERMANENT', 'RETRY_EXHAUSTED'))
            OR (status <> 'FAILED' AND failure_disposition IS NULL)
        );
--rollback ALTER TABLE line_item_watch_signal_processing DROP CONSTRAINT chk_line_item_watch_signal_processing_disposition; ALTER TABLE line_item_watch_signal_processing DROP COLUMN failure_disposition;

--changeset udmconsulting:009-07-extend-application-activity-audit
ALTER TABLE application_activity_audit
    DROP CONSTRAINT chk_application_activity_audit_action,
    DROP CONSTRAINT chk_application_activity_audit_resource_type,
    DROP CONSTRAINT chk_application_activity_audit_shape,
    ADD CONSTRAINT chk_application_activity_audit_action
        CHECK (action IN (
            'PLATFORM_CONNECTION_ACTIVATED', 'PLATFORM_CONNECTION_REAUTHORIZED',
            'PLATFORM_CONNECTION_REAUTHENTICATION_REQUIRED',
            'PLATFORM_CONNECTION_DISCONNECTED', 'ENTITLEMENT_ACTIVATED',
            'ENTITLEMENT_DEACTIVATED', 'LINE_ITEM_SIGNAL_REQUEUED',
            'LINE_ITEM_RECONCILIATION_REQUESTED', 'LINE_ITEM_REPLAY_REQUESTED',
            'LINE_ITEM_RETENTION_EXECUTED', 'LINE_ITEM_FINDING_ACKNOWLEDGED',
            'LINE_ITEM_GAP_ACKNOWLEDGED'
        )),
    ADD CONSTRAINT chk_application_activity_audit_resource_type
        CHECK (resource_type IN (
            'PLATFORM_CONNECTION', 'ENTITLEMENT', 'LINE_ITEM_WATCH_OPERATION'
        )),
    ADD CONSTRAINT chk_application_activity_audit_shape
        CHECK (
            (resource_type = 'PLATFORM_CONNECTION'
                AND action IN (
                    'PLATFORM_CONNECTION_ACTIVATED', 'PLATFORM_CONNECTION_REAUTHORIZED',
                    'PLATFORM_CONNECTION_REAUTHENTICATION_REQUIRED',
                    'PLATFORM_CONNECTION_DISCONNECTED'
                )
                AND connection_id IS NOT NULL
                AND resource_reference = connection_id::text
                AND previous_state IN ('ACTIVE', 'REAUTH_REQUIRED', 'DISCONNECTED')
                AND resulting_state IN ('ACTIVE', 'REAUTH_REQUIRED', 'DISCONNECTED')
                AND (
                    (action = 'PLATFORM_CONNECTION_ACTIVATED'
                        AND previous_state IN ('REAUTH_REQUIRED', 'DISCONNECTED')
                        AND resulting_state = 'ACTIVE')
                    OR (action = 'PLATFORM_CONNECTION_REAUTHORIZED'
                        AND previous_state = 'ACTIVE' AND resulting_state = 'ACTIVE')
                    OR (action = 'PLATFORM_CONNECTION_REAUTHENTICATION_REQUIRED'
                        AND previous_state = 'ACTIVE' AND resulting_state = 'REAUTH_REQUIRED')
                    OR (action = 'PLATFORM_CONNECTION_DISCONNECTED'
                        AND previous_state IN ('ACTIVE', 'REAUTH_REQUIRED')
                        AND resulting_state = 'DISCONNECTED')
                ))
            OR
            (resource_type = 'ENTITLEMENT'
                AND action IN ('ENTITLEMENT_ACTIVATED', 'ENTITLEMENT_DEACTIVATED')
                AND connection_id IS NULL
                AND resource_reference = 'LINE_ITEM_WATCH'
                AND previous_state IN ('ENABLED', 'DISABLED')
                AND resulting_state IN ('ENABLED', 'DISABLED')
                AND (
                    (action = 'ENTITLEMENT_ACTIVATED'
                        AND previous_state = 'DISABLED' AND resulting_state = 'ENABLED')
                    OR (action = 'ENTITLEMENT_DEACTIVATED'
                        AND previous_state = 'ENABLED' AND resulting_state = 'DISABLED')
                ))
            OR
            (resource_type = 'LINE_ITEM_WATCH_OPERATION'
                AND action IN (
                    'LINE_ITEM_SIGNAL_REQUEUED', 'LINE_ITEM_RECONCILIATION_REQUESTED',
                    'LINE_ITEM_REPLAY_REQUESTED', 'LINE_ITEM_RETENTION_EXECUTED',
                    'LINE_ITEM_FINDING_ACKNOWLEDGED', 'LINE_ITEM_GAP_ACKNOWLEDGED'
                )
                AND connection_id IS NOT NULL
                AND resource_reference ~ '^[0-9a-fA-F-]{36}$'
                AND previous_state IN (
                    'REQUESTED', 'FAILED', 'OPEN', 'PREVIEWED', 'POSSIBLE_GAP'
                )
                AND resulting_state IN ('PENDING', 'ACKNOWLEDGED', 'EXECUTED'))
        );
--rollback ALTER TABLE application_activity_audit DROP CONSTRAINT chk_application_activity_audit_shape; ALTER TABLE application_activity_audit DROP CONSTRAINT chk_application_activity_audit_resource_type; ALTER TABLE application_activity_audit DROP CONSTRAINT chk_application_activity_audit_action;
