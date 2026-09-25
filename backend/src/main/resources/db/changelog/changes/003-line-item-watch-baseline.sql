--liquibase formatted sql

--changeset udmconsulting:003-01-add-connection-tenant-identity
ALTER TABLE platform_connection
    ADD CONSTRAINT uq_platform_connection_tenant_id_id UNIQUE (tenant_id, id);
--rollback ALTER TABLE platform_connection DROP CONSTRAINT uq_platform_connection_tenant_id_id;

--changeset udmconsulting:003-02-create-line-item-watch-line-item
CREATE TABLE line_item_watch_line_item (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    external_line_item_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_line_item_watch_line_item PRIMARY KEY (id),
    CONSTRAINT uq_line_item_watch_line_item_external
        UNIQUE (tenant_id, connection_id, external_line_item_id),
    CONSTRAINT uq_line_item_watch_line_item_owner
        UNIQUE (tenant_id, connection_id, id),
    CONSTRAINT fk_line_item_watch_line_item_connection
        FOREIGN KEY (tenant_id, connection_id)
        REFERENCES platform_connection (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_line_item_external_id
        CHECK (
            external_line_item_id <> ''
            AND external_line_item_id = btrim(external_line_item_id)
        )
);
--rollback DROP TABLE line_item_watch_line_item;

--changeset udmconsulting:003-03-create-line-item-watch-snapshot
CREATE TABLE line_item_watch_snapshot (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    line_item_id UUID NOT NULL,
    snapshot_kind VARCHAR(16) NOT NULL,
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
    provider_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    provider_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    persisted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_line_item_watch_snapshot PRIMARY KEY (line_item_id, snapshot_kind),
    CONSTRAINT uq_line_item_watch_snapshot_owner
        UNIQUE (tenant_id, connection_id, line_item_id, snapshot_kind),
    CONSTRAINT fk_line_item_watch_snapshot_line_item
        FOREIGN KEY (tenant_id, connection_id, line_item_id)
        REFERENCES line_item_watch_line_item (tenant_id, connection_id, id)
        ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_snapshot_kind
        CHECK (snapshot_kind IN ('BASELINE', 'LATEST')),
    CONSTRAINT chk_line_item_watch_snapshot_billing_frequency
        CHECK (billing_frequency IS NULL OR (
            billing_frequency <> '' AND billing_frequency = btrim(billing_frequency)
        )),
    CONSTRAINT chk_line_item_watch_snapshot_recurring_period
        CHECK (recurring_billing_period IS NULL OR (
            recurring_billing_period <> ''
            AND recurring_billing_period = btrim(recurring_billing_period)
            AND recurring_billing_period LIKE 'P%'
        )),
    CONSTRAINT chk_line_item_watch_snapshot_billing_start
        CHECK (
            (billing_start_date IS NOT NULL
                AND billing_start_delay_unit IS NULL
                AND billing_start_delay_count IS NULL)
            OR
            (billing_start_date IS NULL
                AND billing_start_delay_unit IN ('DAYS', 'MONTHS')
                AND billing_start_delay_count >= 0)
            OR
            (billing_start_date IS NULL
                AND billing_start_delay_unit IS NULL
                AND billing_start_delay_count IS NULL)
        )
);

CREATE INDEX idx_line_item_watch_snapshot_owner_kind
    ON line_item_watch_snapshot (tenant_id, connection_id, snapshot_kind, line_item_id);
--rollback DROP TABLE line_item_watch_snapshot;

--changeset udmconsulting:003-04-create-line-item-watch-snapshot-deal
CREATE TABLE line_item_watch_snapshot_deal (
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    line_item_id UUID NOT NULL,
    snapshot_kind VARCHAR(16) NOT NULL,
    external_deal_id VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_line_item_watch_snapshot_deal
        PRIMARY KEY (line_item_id, snapshot_kind, external_deal_id),
    CONSTRAINT fk_line_item_watch_snapshot_deal_snapshot
        FOREIGN KEY (tenant_id, connection_id, line_item_id, snapshot_kind)
        REFERENCES line_item_watch_snapshot (
            tenant_id, connection_id, line_item_id, snapshot_kind
        ) ON DELETE CASCADE,
    CONSTRAINT chk_line_item_watch_snapshot_deal_external_id
        CHECK (
            external_deal_id <> ''
            AND external_deal_id = btrim(external_deal_id)
        )
);

CREATE INDEX idx_line_item_watch_snapshot_deal_lookup
    ON line_item_watch_snapshot_deal (
        tenant_id, connection_id, external_deal_id, snapshot_kind, line_item_id
    );
--rollback DROP TABLE line_item_watch_snapshot_deal;
