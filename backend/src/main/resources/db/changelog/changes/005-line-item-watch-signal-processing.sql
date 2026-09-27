--liquibase formatted sql

--changeset udmconsulting:005-01-extend-snapshot-projection-model
ALTER TABLE line_item_watch_change_signal
    ADD CONSTRAINT uq_line_item_watch_change_signal_owner
        UNIQUE (tenant_id, connection_id, id);

ALTER TABLE line_item_watch_snapshot
    DROP CONSTRAINT chk_line_item_watch_snapshot_kind,
    ALTER COLUMN provider_created_at DROP NOT NULL,
    ALTER COLUMN provider_updated_at DROP NOT NULL,
    ADD COLUMN known_properties TEXT[] NOT NULL DEFAULT ARRAY[
        'name',
        'quantity',
        'price',
        'discount',
        'hs_discount_percentage',
        'recurringbillingfrequency',
        'hs_recurring_billing_start_date',
        'hs_billing_start_delay_days',
        'hs_billing_start_delay_months',
        'hs_recurring_billing_period'
    ]::TEXT[],
    ADD COLUMN deal_set_complete BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT chk_line_item_watch_snapshot_kind
        CHECK (snapshot_kind IN ('BASELINE', 'OBSERVED', 'LATEST')),
    ADD CONSTRAINT chk_line_item_watch_snapshot_known_properties
        CHECK (
            array_position(known_properties, NULL) IS NULL
            AND known_properties <@ ARRAY[
                'name',
                'quantity',
                'price',
                'discount',
                'hs_discount_percentage',
                'recurringbillingfrequency',
                'hs_recurring_billing_start_date',
                'hs_billing_start_delay_days',
                'hs_billing_start_delay_months',
                'hs_recurring_billing_period'
            ]::TEXT[]
        ),
    ADD CONSTRAINT chk_line_item_watch_snapshot_complete_checkpoint
        CHECK (
            snapshot_kind = 'LATEST'
            OR (
                provider_created_at IS NOT NULL
                AND provider_updated_at IS NOT NULL
                AND cardinality(known_properties) = 10
                AND known_properties @> ARRAY[
                    'name',
                    'quantity',
                    'price',
                    'discount',
                    'hs_discount_percentage',
                    'recurringbillingfrequency',
                    'hs_recurring_billing_start_date',
                    'hs_billing_start_delay_days',
                    'hs_billing_start_delay_months',
                    'hs_recurring_billing_period'
                ]::TEXT[]
                AND deal_set_complete
                AND deleted_at IS NULL
            )
        );

INSERT INTO line_item_watch_snapshot (
    tenant_id, connection_id, line_item_id, snapshot_kind, name,
    quantity, unit_price, unit_discount, discount_percentage,
    billing_frequency, billing_start_date, billing_start_delay_unit,
    billing_start_delay_count, recurring_billing_period,
    provider_created_at, provider_updated_at, observed_at, persisted_at,
    known_properties, deal_set_complete, deleted_at
)
SELECT
    tenant_id, connection_id, line_item_id, 'OBSERVED', name,
    quantity, unit_price, unit_discount, discount_percentage,
    billing_frequency, billing_start_date, billing_start_delay_unit,
    billing_start_delay_count, recurring_billing_period,
    provider_created_at, provider_updated_at, observed_at, persisted_at,
    known_properties, deal_set_complete, NULL
FROM line_item_watch_snapshot
WHERE snapshot_kind = 'LATEST';

INSERT INTO line_item_watch_snapshot_deal (
    tenant_id, connection_id, line_item_id, snapshot_kind,
    external_deal_id, recorded_at
)
SELECT
    tenant_id, connection_id, line_item_id, 'OBSERVED',
    external_deal_id, recorded_at
FROM line_item_watch_snapshot_deal
WHERE snapshot_kind = 'LATEST';
--rollback ALTER TABLE line_item_watch_snapshot DROP CONSTRAINT chk_line_item_watch_snapshot_complete_checkpoint; ALTER TABLE line_item_watch_snapshot DROP CONSTRAINT chk_line_item_watch_snapshot_known_properties; ALTER TABLE line_item_watch_snapshot DROP COLUMN deleted_at; ALTER TABLE line_item_watch_snapshot DROP COLUMN deal_set_complete; ALTER TABLE line_item_watch_snapshot DROP COLUMN known_properties; ALTER TABLE line_item_watch_snapshot ALTER COLUMN provider_updated_at SET NOT NULL; ALTER TABLE line_item_watch_snapshot ALTER COLUMN provider_created_at SET NOT NULL; ALTER TABLE line_item_watch_snapshot DROP CONSTRAINT chk_line_item_watch_snapshot_kind; ALTER TABLE line_item_watch_snapshot ADD CONSTRAINT chk_line_item_watch_snapshot_kind CHECK (snapshot_kind IN ('BASELINE', 'LATEST')); ALTER TABLE line_item_watch_change_signal DROP CONSTRAINT uq_line_item_watch_change_signal_owner;

--changeset udmconsulting:005-02-create-signal-processing-state
CREATE TABLE line_item_watch_signal_processing (
    signal_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token UUID,
    claimed_at TIMESTAMP WITH TIME ZONE,
    lease_until TIMESTAMP WITH TIME ZONE,
    processed_at TIMESTAMP WITH TIME ZONE,
    failed_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_line_item_watch_signal_processing PRIMARY KEY (signal_id),
    CONSTRAINT fk_line_item_watch_signal_processing_signal
        FOREIGN KEY (tenant_id, connection_id, signal_id)
        REFERENCES line_item_watch_change_signal (tenant_id, connection_id, id)
        ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_signal_processing_status
        CHECK (status IN ('PENDING', 'CLAIMED', 'PROCESSED', 'FAILED')),
    CONSTRAINT chk_line_item_watch_signal_processing_attempts
        CHECK (attempt_count >= 0),
    CONSTRAINT chk_line_item_watch_signal_processing_error
        CHECK (
            last_error_code IS NULL
            OR (
                last_error_code <> ''
                AND last_error_code = upper(last_error_code)
                AND char_length(last_error_code) <= 64
            )
        ),
    CONSTRAINT chk_line_item_watch_signal_processing_shape
        CHECK (
            (status = 'PENDING'
                AND claim_token IS NULL AND claimed_at IS NULL AND lease_until IS NULL
                AND processed_at IS NULL AND failed_at IS NULL)
            OR
            (status = 'CLAIMED'
                AND claim_token IS NOT NULL AND claimed_at IS NOT NULL AND lease_until > claimed_at
                AND processed_at IS NULL AND failed_at IS NULL)
            OR
            (status = 'PROCESSED'
                AND claim_token IS NULL AND claimed_at IS NULL AND lease_until IS NULL
                AND processed_at IS NOT NULL AND failed_at IS NULL AND last_error_code IS NULL)
            OR
            (status = 'FAILED'
                AND claim_token IS NULL AND claimed_at IS NULL AND lease_until IS NULL
                AND processed_at IS NULL AND failed_at IS NOT NULL AND last_error_code IS NOT NULL)
        )
);

INSERT INTO line_item_watch_signal_processing (
    signal_id, tenant_id, connection_id, status, next_attempt_at
)
SELECT id, tenant_id, connection_id, 'PENDING', CURRENT_TIMESTAMP
FROM line_item_watch_change_signal;

CREATE INDEX idx_line_item_watch_signal_processing_due
    ON line_item_watch_signal_processing (next_attempt_at, signal_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_line_item_watch_signal_processing_expired_claim
    ON line_item_watch_signal_processing (lease_until, signal_id)
    WHERE status = 'CLAIMED';

CREATE INDEX idx_line_item_watch_signal_processing_failed
    ON line_item_watch_signal_processing (tenant_id, connection_id, failed_at)
    WHERE status = 'FAILED';
--rollback DROP TABLE line_item_watch_signal_processing;

--changeset udmconsulting:005-03-create-semantic-audit-projection
CREATE TABLE line_item_watch_audit_event (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    line_item_id UUID NOT NULL,
    semantic_key BYTEA NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    property_name VARCHAR(128),
    before_state VARCHAR(16),
    before_value TEXT,
    after_state VARCHAR(16),
    after_value TEXT,
    external_deal_id VARCHAR(255),
    projected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_line_item_watch_audit_event PRIMARY KEY (id),
    CONSTRAINT uq_line_item_watch_audit_event_owner
        UNIQUE (tenant_id, connection_id, id),
    CONSTRAINT uq_line_item_watch_audit_event_semantic
        UNIQUE (tenant_id, connection_id, semantic_key),
    CONSTRAINT fk_line_item_watch_audit_event_line_item
        FOREIGN KEY (tenant_id, connection_id, line_item_id)
        REFERENCES line_item_watch_line_item (tenant_id, connection_id, id)
        ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_audit_event_key
        CHECK (octet_length(semantic_key) = 32),
    CONSTRAINT chk_line_item_watch_audit_event_type
        CHECK (event_type IN (
            'CREATED', 'PROPERTY_CHANGED', 'DEAL_ASSOCIATED',
            'DEAL_DISASSOCIATED', 'DELETED'
        )),
    CONSTRAINT chk_line_item_watch_audit_event_property
        CHECK (property_name IS NULL OR property_name IN (
            'name', 'quantity', 'price', 'discount', 'hs_discount_percentage',
            'recurringbillingfrequency', 'hs_recurring_billing_start_date',
            'hs_billing_start_delay_days', 'hs_billing_start_delay_months',
            'hs_recurring_billing_period'
        )),
    CONSTRAINT chk_line_item_watch_audit_event_state
        CHECK (
            (before_state IS NULL OR before_state IN ('UNKNOWN', 'ABSENT', 'PRESENT', 'VALUE'))
            AND (after_state IS NULL OR after_state IN ('UNKNOWN', 'ABSENT', 'PRESENT', 'VALUE'))
            AND ((before_state = 'VALUE') = (before_value IS NOT NULL))
            AND ((after_state = 'VALUE') = (after_value IS NOT NULL))
        ),
    CONSTRAINT chk_line_item_watch_audit_event_deal_id
        CHECK (
            external_deal_id IS NULL
            OR (external_deal_id <> '' AND external_deal_id = btrim(external_deal_id))
        ),
    CONSTRAINT chk_line_item_watch_audit_event_shape
        CHECK (
            (event_type = 'PROPERTY_CHANGED'
                AND property_name IS NOT NULL
                AND before_state IN ('UNKNOWN', 'ABSENT', 'VALUE')
                AND after_state IN ('UNKNOWN', 'ABSENT', 'VALUE')
                AND external_deal_id IS NULL)
            OR
            (event_type IN ('DEAL_ASSOCIATED', 'DEAL_DISASSOCIATED')
                AND property_name IS NULL
                AND before_state IN ('UNKNOWN', 'ABSENT', 'PRESENT')
                AND after_state IN ('ABSENT', 'PRESENT')
                AND external_deal_id IS NOT NULL)
            OR
            (event_type IN ('CREATED', 'DELETED')
                AND property_name IS NULL
                AND before_state IN ('UNKNOWN', 'ABSENT', 'PRESENT')
                AND after_state IN ('ABSENT', 'PRESENT')
                AND external_deal_id IS NULL)
        )
);

CREATE TABLE line_item_watch_audit_event_source (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    audit_event_id UUID NOT NULL,
    source_signal_id UUID NOT NULL,
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_line_item_watch_audit_event_source
        PRIMARY KEY (audit_event_id, source_signal_id),
    CONSTRAINT fk_line_item_watch_audit_event_source_event
        FOREIGN KEY (tenant_id, connection_id, audit_event_id)
        REFERENCES line_item_watch_audit_event (tenant_id, connection_id, id)
        ON DELETE CASCADE
);

COMMENT ON COLUMN line_item_watch_audit_event_source.source_signal_id IS
    'Durable provenance identifier only; deliberately not a foreign key so raw-signal retention cannot erase semantic audit history.';

CREATE TABLE line_item_watch_audit_event_deal_context (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    audit_event_id UUID NOT NULL,
    external_deal_id VARCHAR(255) NOT NULL,
    CONSTRAINT pk_line_item_watch_audit_event_deal_context
        PRIMARY KEY (audit_event_id, external_deal_id),
    CONSTRAINT fk_line_item_watch_audit_event_deal_context_event
        FOREIGN KEY (tenant_id, connection_id, audit_event_id)
        REFERENCES line_item_watch_audit_event (tenant_id, connection_id, id)
        ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_audit_event_context_deal_id
        CHECK (external_deal_id <> '' AND external_deal_id = btrim(external_deal_id))
);

CREATE INDEX idx_line_item_watch_audit_event_chronology
    ON line_item_watch_audit_event (
        tenant_id, connection_id, line_item_id, occurred_at, id
    );

CREATE INDEX idx_line_item_watch_audit_event_deal_lookup
    ON line_item_watch_audit_event_deal_context (
        tenant_id, connection_id, external_deal_id, audit_event_id
    );

CREATE INDEX idx_line_item_watch_audit_event_source_signal
    ON line_item_watch_audit_event_source (
        tenant_id, connection_id, source_signal_id
    );
--rollback DROP TABLE line_item_watch_audit_event_deal_context; DROP TABLE line_item_watch_audit_event_source; DROP TABLE line_item_watch_audit_event;

--changeset udmconsulting:005-04-require-semantic-audit-states
ALTER TABLE line_item_watch_audit_event
    ALTER COLUMN before_state SET NOT NULL,
    ALTER COLUMN after_state SET NOT NULL;
--rollback ALTER TABLE line_item_watch_audit_event ALTER COLUMN after_state DROP NOT NULL; ALTER TABLE line_item_watch_audit_event ALTER COLUMN before_state DROP NOT NULL;

--changeset udmconsulting:005-05-index-line-item-signal-chronology
CREATE INDEX idx_line_item_watch_change_signal_line_item_occurrence
    ON line_item_watch_change_signal (
        tenant_id, connection_id, external_line_item_id,
        occurred_at, provider_deduplication_key, id
    );
--rollback DROP INDEX idx_line_item_watch_change_signal_line_item_occurrence;

--changeset udmconsulting:005-06-enforce-sparse-snapshot-values
ALTER TABLE line_item_watch_snapshot
    ADD CONSTRAINT chk_line_item_watch_snapshot_known_values
        CHECK (
            (name IS NULL OR known_properties @> ARRAY['name']::TEXT[])
            AND (quantity IS NULL OR known_properties @> ARRAY['quantity']::TEXT[])
            AND (unit_price IS NULL OR known_properties @> ARRAY['price']::TEXT[])
            AND (unit_discount IS NULL OR known_properties @> ARRAY['discount']::TEXT[])
            AND (discount_percentage IS NULL
                OR known_properties @> ARRAY['hs_discount_percentage']::TEXT[])
            AND (billing_frequency IS NULL
                OR known_properties @> ARRAY['recurringbillingfrequency']::TEXT[])
            AND (billing_start_date IS NULL
                OR known_properties @> ARRAY['hs_recurring_billing_start_date']::TEXT[])
            AND (billing_start_delay_unit IS NULL
                OR (billing_start_delay_unit = 'DAYS'
                    AND known_properties @> ARRAY['hs_billing_start_delay_days']::TEXT[])
                OR (billing_start_delay_unit = 'MONTHS'
                    AND known_properties @> ARRAY['hs_billing_start_delay_months']::TEXT[]))
            AND (recurring_billing_period IS NULL
                OR known_properties @> ARRAY['hs_recurring_billing_period']::TEXT[])
        );
--rollback ALTER TABLE line_item_watch_snapshot DROP CONSTRAINT chk_line_item_watch_snapshot_known_values;

--changeset udmconsulting:005-07-enforce-semantic-audit-directions
ALTER TABLE line_item_watch_audit_event
    DROP CONSTRAINT chk_line_item_watch_audit_event_shape,
    ADD CONSTRAINT chk_line_item_watch_audit_event_shape
        CHECK (
            (event_type = 'PROPERTY_CHANGED'
                AND property_name IS NOT NULL
                AND before_state IN ('UNKNOWN', 'ABSENT', 'VALUE')
                AND after_state IN ('UNKNOWN', 'ABSENT', 'VALUE')
                AND external_deal_id IS NULL)
            OR
            (event_type = 'DEAL_ASSOCIATED'
                AND property_name IS NULL
                AND before_state IN ('UNKNOWN', 'ABSENT')
                AND after_state = 'PRESENT'
                AND external_deal_id IS NOT NULL)
            OR
            (event_type = 'DEAL_DISASSOCIATED'
                AND property_name IS NULL
                AND before_state IN ('UNKNOWN', 'PRESENT')
                AND after_state = 'ABSENT'
                AND external_deal_id IS NOT NULL)
            OR
            (event_type = 'CREATED'
                AND property_name IS NULL
                AND before_state = 'ABSENT'
                AND after_state = 'PRESENT'
                AND external_deal_id IS NULL)
            OR
            (event_type = 'DELETED'
                AND property_name IS NULL
                AND before_state IN ('UNKNOWN', 'PRESENT')
                AND after_state = 'ABSENT'
                AND external_deal_id IS NULL)
        );
--rollback ALTER TABLE line_item_watch_audit_event DROP CONSTRAINT chk_line_item_watch_audit_event_shape; ALTER TABLE line_item_watch_audit_event ADD CONSTRAINT chk_line_item_watch_audit_event_shape CHECK ((event_type = 'PROPERTY_CHANGED' AND property_name IS NOT NULL AND before_state IN ('UNKNOWN', 'ABSENT', 'VALUE') AND after_state IN ('UNKNOWN', 'ABSENT', 'VALUE') AND external_deal_id IS NULL) OR (event_type IN ('DEAL_ASSOCIATED', 'DEAL_DISASSOCIATED') AND property_name IS NULL AND before_state IN ('UNKNOWN', 'ABSENT', 'PRESENT') AND after_state IN ('ABSENT', 'PRESENT') AND external_deal_id IS NOT NULL) OR (event_type IN ('CREATED', 'DELETED') AND property_name IS NULL AND before_state IN ('UNKNOWN', 'ABSENT', 'PRESENT') AND after_state IN ('ABSENT', 'PRESENT') AND external_deal_id IS NULL));
