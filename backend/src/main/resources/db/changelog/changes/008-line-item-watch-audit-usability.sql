--liquibase formatted sql

--changeset udmconsulting:008-01-add-latest-name-trigram-search
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM pg_available_extensions WHERE name = 'pg_trgm';
CREATE EXTENSION IF NOT EXISTS pg_trgm;

--changeset udmconsulting:008-02-add-latest-name-trigram-index splitStatements:false
DO $migration$
DECLARE
    extension_schema TEXT;
BEGIN
    SELECT namespace.nspname
    INTO extension_schema
    FROM pg_extension extension
    JOIN pg_namespace namespace ON namespace.oid = extension.extnamespace
    WHERE extension.extname = 'pg_trgm';

    IF extension_schema IS NULL THEN
        RAISE EXCEPTION 'pg_trgm is not installed';
    END IF;

    EXECUTE format(
        'CREATE INDEX idx_liw_snapshot_latest_name_trgm '
        'ON line_item_watch_snapshot '
        'USING GIN (lower(name) %I.gin_trgm_ops) '
        'WHERE snapshot_kind = ''LATEST'' AND name IS NOT NULL',
        extension_schema
    );
END
$migration$;
--rollback DROP INDEX idx_liw_snapshot_latest_name_trgm;

--changeset udmconsulting:008-03-add-deal-event-filter-dimensions
ALTER TABLE line_item_watch_audit_event_deal_context
    ADD COLUMN line_item_id UUID,
    ADD COLUMN event_type VARCHAR(32),
    ADD COLUMN property_name VARCHAR(128);

UPDATE line_item_watch_audit_event_deal_context context
SET line_item_id = event.line_item_id,
    event_type = event.event_type,
    property_name = event.property_name
FROM line_item_watch_audit_event event
WHERE event.tenant_id = context.tenant_id
  AND event.connection_id = context.connection_id
  AND event.id = context.audit_event_id;

ALTER TABLE line_item_watch_audit_event
    ADD CONSTRAINT uq_liw_audit_event_filter_identity
        UNIQUE (
            tenant_id, connection_id, id, occurred_at, semantic_key,
            line_item_id, event_type
        ),
    ADD CONSTRAINT uq_liw_audit_event_filter_property
        UNIQUE (tenant_id, connection_id, id, property_name);

ALTER TABLE line_item_watch_audit_event_deal_context
    ALTER COLUMN line_item_id SET NOT NULL,
    ALTER COLUMN event_type SET NOT NULL,
    ADD CONSTRAINT chk_liw_audit_context_event_type
        CHECK (event_type IN (
            'CREATED', 'PROPERTY_CHANGED', 'DEAL_ASSOCIATED',
            'DEAL_DISASSOCIATED', 'DELETED'
        )),
    ADD CONSTRAINT chk_liw_audit_context_property
        CHECK (property_name IS NULL OR property_name IN (
            'name', 'quantity', 'price', 'discount', 'hs_discount_percentage',
            'recurringbillingfrequency', 'hs_recurring_billing_start_date',
            'hs_billing_start_delay_days', 'hs_billing_start_delay_months',
            'hs_recurring_billing_period'
        )),
    ADD CONSTRAINT chk_liw_audit_context_filter_shape
        CHECK ((event_type = 'PROPERTY_CHANGED') = (property_name IS NOT NULL)),
    DROP CONSTRAINT fk_line_item_watch_audit_event_deal_context_event,
    ADD CONSTRAINT fk_liw_audit_context_event_filter_identity
        FOREIGN KEY (
            tenant_id, connection_id, audit_event_id, occurred_at, semantic_key,
            line_item_id, event_type
        ) REFERENCES line_item_watch_audit_event (
            tenant_id, connection_id, id, occurred_at, semantic_key,
            line_item_id, event_type
        ) ON DELETE CASCADE,
    ADD CONSTRAINT fk_liw_audit_context_event_filter_property
        FOREIGN KEY (tenant_id, connection_id, audit_event_id, property_name)
        REFERENCES line_item_watch_audit_event (
            tenant_id, connection_id, id, property_name
        ) ON DELETE CASCADE;

CREATE INDEX idx_liw_audit_context_line_item_chronology
    ON line_item_watch_audit_event_deal_context (
        tenant_id, connection_id, external_deal_id, line_item_id,
        occurred_at DESC, semantic_key DESC
    ) INCLUDE (audit_event_id);

CREATE INDEX idx_liw_audit_context_event_type_chronology
    ON line_item_watch_audit_event_deal_context (
        tenant_id, connection_id, external_deal_id, event_type,
        occurred_at DESC, semantic_key DESC
    ) INCLUDE (audit_event_id);

CREATE INDEX idx_liw_audit_context_property_chronology
    ON line_item_watch_audit_event_deal_context (
        tenant_id, connection_id, external_deal_id, property_name,
        occurred_at DESC, semantic_key DESC
    ) INCLUDE (audit_event_id)
    WHERE property_name IS NOT NULL;
--rollback DROP INDEX idx_liw_audit_context_property_chronology; DROP INDEX idx_liw_audit_context_event_type_chronology; DROP INDEX idx_liw_audit_context_line_item_chronology; ALTER TABLE line_item_watch_audit_event_deal_context DROP CONSTRAINT fk_liw_audit_context_event_filter_property, DROP CONSTRAINT fk_liw_audit_context_event_filter_identity, DROP CONSTRAINT chk_liw_audit_context_filter_shape, DROP CONSTRAINT chk_liw_audit_context_property, DROP CONSTRAINT chk_liw_audit_context_event_type, ADD CONSTRAINT fk_line_item_watch_audit_event_deal_context_event FOREIGN KEY (tenant_id, connection_id, audit_event_id, occurred_at, semantic_key) REFERENCES line_item_watch_audit_event (tenant_id, connection_id, id, occurred_at, semantic_key) ON DELETE CASCADE, DROP COLUMN property_name, DROP COLUMN event_type, DROP COLUMN line_item_id; ALTER TABLE line_item_watch_audit_event DROP CONSTRAINT uq_liw_audit_event_filter_property, DROP CONSTRAINT uq_liw_audit_event_filter_identity;
