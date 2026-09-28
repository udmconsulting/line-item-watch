--liquibase formatted sql

--changeset udmconsulting:006-01-add-history-coverage-to-latest-projection
ALTER TABLE line_item_watch_snapshot
    ADD COLUMN history_coverage_mode VARCHAR(32),
    ADD COLUMN history_observed_from TIMESTAMP WITH TIME ZONE;

UPDATE line_item_watch_snapshot latest
SET history_coverage_mode = CASE
        WHEN baseline.line_item_id IS NOT NULL THEN 'BASELINE_ANCHORED'
        ELSE 'SIGNAL_FIRST'
    END,
    history_observed_from = CASE
        WHEN baseline.line_item_id IS NOT NULL THEN baseline.observed_at
        ELSE LEAST(
            checkpoint.first_observed_at,
            signal.first_occurred_at,
            audit.first_occurred_at,
            latest.observed_at
        )
    END
FROM line_item_watch_line_item item
LEFT JOIN line_item_watch_snapshot baseline
  ON baseline.line_item_id = item.id
 AND baseline.snapshot_kind = 'BASELINE'
 AND NOT EXISTS (
    SELECT 1
    FROM line_item_watch_change_signal deleted_signal
    JOIN line_item_watch_signal_processing deleted_processing
      ON deleted_processing.tenant_id = deleted_signal.tenant_id
     AND deleted_processing.connection_id = deleted_signal.connection_id
     AND deleted_processing.signal_id = deleted_signal.id
     AND deleted_processing.status = 'PROCESSED'
    WHERE deleted_signal.tenant_id = item.tenant_id
      AND deleted_signal.connection_id = item.connection_id
      AND deleted_signal.external_line_item_id = item.external_line_item_id
      AND deleted_signal.signal_type = 'DELETED'
      AND deleted_signal.occurred_at < baseline.observed_at
 )
 AND NOT EXISTS (
    SELECT 1
    FROM line_item_watch_audit_event deleted_event
    WHERE deleted_event.tenant_id = item.tenant_id
      AND deleted_event.connection_id = item.connection_id
      AND deleted_event.line_item_id = item.id
      AND deleted_event.event_type = 'DELETED'
      AND deleted_event.occurred_at < baseline.observed_at
 )
LEFT JOIN LATERAL (
    SELECT MIN(checkpoint.observed_at) AS first_observed_at
    FROM line_item_watch_snapshot checkpoint
    WHERE checkpoint.line_item_id = item.id
      AND checkpoint.snapshot_kind = 'OBSERVED'
) checkpoint ON TRUE
LEFT JOIN LATERAL (
    SELECT MIN(change.occurred_at) AS first_occurred_at
    FROM line_item_watch_change_signal change
    JOIN line_item_watch_signal_processing processing
      ON processing.tenant_id = change.tenant_id
     AND processing.connection_id = change.connection_id
     AND processing.signal_id = change.id
     AND processing.status = 'PROCESSED'
    WHERE change.tenant_id = item.tenant_id
      AND change.connection_id = item.connection_id
      AND change.external_line_item_id = item.external_line_item_id
) signal ON TRUE
LEFT JOIN LATERAL (
    SELECT MIN(event.occurred_at) AS first_occurred_at
    FROM line_item_watch_audit_event event
    WHERE event.tenant_id = item.tenant_id
      AND event.connection_id = item.connection_id
      AND event.line_item_id = item.id
) audit ON TRUE
WHERE latest.line_item_id = item.id
  AND latest.snapshot_kind = 'LATEST';

ALTER TABLE line_item_watch_snapshot
    ADD CONSTRAINT chk_line_item_watch_snapshot_history_coverage
        CHECK (
            (snapshot_kind = 'LATEST'
                AND history_coverage_mode IN ('BASELINE_ANCHORED', 'SIGNAL_FIRST')
                AND history_observed_from IS NOT NULL)
            OR
            (snapshot_kind <> 'LATEST'
                AND history_coverage_mode IS NULL
                AND history_observed_from IS NULL)
        );
--rollback ALTER TABLE line_item_watch_snapshot DROP CONSTRAINT chk_line_item_watch_snapshot_history_coverage; ALTER TABLE line_item_watch_snapshot DROP COLUMN history_observed_from; ALTER TABLE line_item_watch_snapshot DROP COLUMN history_coverage_mode;

--changeset udmconsulting:006-02-add-deal-audit-keyset-chronology
ALTER TABLE line_item_watch_audit_event_deal_context
    ADD COLUMN occurred_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN semantic_key BYTEA;

UPDATE line_item_watch_audit_event_deal_context context
SET occurred_at = event.occurred_at,
    semantic_key = event.semantic_key
FROM line_item_watch_audit_event event
WHERE event.tenant_id = context.tenant_id
  AND event.connection_id = context.connection_id
  AND event.id = context.audit_event_id;

ALTER TABLE line_item_watch_audit_event
    ADD CONSTRAINT uq_line_item_watch_audit_event_context_identity
        UNIQUE (tenant_id, connection_id, id, occurred_at, semantic_key);

ALTER TABLE line_item_watch_audit_event_deal_context
    ALTER COLUMN occurred_at SET NOT NULL,
    ALTER COLUMN semantic_key SET NOT NULL,
    ADD CONSTRAINT chk_line_item_watch_audit_event_context_key
        CHECK (octet_length(semantic_key) = 32),
    DROP CONSTRAINT fk_line_item_watch_audit_event_deal_context_event,
    ADD CONSTRAINT fk_line_item_watch_audit_event_deal_context_event
        FOREIGN KEY (tenant_id, connection_id, audit_event_id, occurred_at, semantic_key)
        REFERENCES line_item_watch_audit_event (
            tenant_id, connection_id, id, occurred_at, semantic_key
        ) ON DELETE CASCADE;

DROP INDEX idx_line_item_watch_audit_event_deal_lookup;

CREATE INDEX idx_line_item_watch_audit_event_deal_chronology
    ON line_item_watch_audit_event_deal_context (
        tenant_id, connection_id, external_deal_id,
        occurred_at DESC, semantic_key DESC
    ) INCLUDE (audit_event_id);
--rollback DROP INDEX idx_line_item_watch_audit_event_deal_chronology; CREATE INDEX idx_line_item_watch_audit_event_deal_lookup ON line_item_watch_audit_event_deal_context (tenant_id, connection_id, external_deal_id, audit_event_id); ALTER TABLE line_item_watch_audit_event_deal_context DROP CONSTRAINT fk_line_item_watch_audit_event_deal_context_event; ALTER TABLE line_item_watch_audit_event_deal_context ADD CONSTRAINT fk_line_item_watch_audit_event_deal_context_event FOREIGN KEY (tenant_id, connection_id, audit_event_id) REFERENCES line_item_watch_audit_event (tenant_id, connection_id, id) ON DELETE CASCADE; ALTER TABLE line_item_watch_audit_event_deal_context DROP CONSTRAINT chk_line_item_watch_audit_event_context_key; ALTER TABLE line_item_watch_audit_event_deal_context DROP COLUMN semantic_key; ALTER TABLE line_item_watch_audit_event_deal_context DROP COLUMN occurred_at; ALTER TABLE line_item_watch_audit_event DROP CONSTRAINT uq_line_item_watch_audit_event_context_identity;
