--liquibase formatted sql

--changeset udmconsulting:004-01-create-line-item-watch-change-signal
CREATE TABLE line_item_watch_change_signal (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    provider_subscription_id VARCHAR(255) NOT NULL,
    provider_deduplication_key BYTEA NOT NULL,
    external_line_item_id VARCHAR(255) NOT NULL,
    signal_type VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    property_name VARCHAR(128),
    property_value TEXT,
    external_deal_id VARCHAR(255),
    association_action VARCHAR(16),
    association_type_id VARCHAR(64),
    association_category VARCHAR(64),
    CONSTRAINT pk_line_item_watch_change_signal PRIMARY KEY (id),
    CONSTRAINT fk_line_item_watch_change_signal_connection
        FOREIGN KEY (tenant_id, connection_id)
        REFERENCES platform_connection (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_line_item_watch_change_signal_dedup
        UNIQUE (tenant_id, connection_id, provider_deduplication_key),
    CONSTRAINT chk_line_item_watch_change_signal_dedup_length
        CHECK (octet_length(provider_deduplication_key) = 32),
    CONSTRAINT chk_line_item_watch_change_signal_event_id
        CHECK (provider_event_id <> '' AND provider_event_id = btrim(provider_event_id)),
    CONSTRAINT chk_line_item_watch_change_signal_subscription_id
        CHECK (
            provider_subscription_id <> ''
            AND provider_subscription_id = btrim(provider_subscription_id)
        ),
    CONSTRAINT chk_line_item_watch_change_signal_line_item_id
        CHECK (
            external_line_item_id <> ''
            AND external_line_item_id = btrim(external_line_item_id)
        ),
    CONSTRAINT chk_line_item_watch_change_signal_type
        CHECK (signal_type IN (
            'CREATED', 'DELETED', 'PROPERTY_CHANGED', 'ASSOCIATION_CHANGED'
        )),
    CONSTRAINT chk_line_item_watch_change_signal_property_name
        CHECK (property_name IS NULL OR property_name IN (
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
        )),
    CONSTRAINT chk_line_item_watch_change_signal_property_value_length
        CHECK (property_value IS NULL OR char_length(property_value) <= 65535),
    CONSTRAINT chk_line_item_watch_change_signal_deal_id
        CHECK (
            external_deal_id IS NULL
            OR (external_deal_id <> '' AND external_deal_id = btrim(external_deal_id))
        ),
    CONSTRAINT chk_line_item_watch_change_signal_association_action
        CHECK (association_action IS NULL OR association_action IN ('ADDED', 'REMOVED')),
    CONSTRAINT chk_line_item_watch_change_signal_association_type_id
        CHECK (
            association_type_id IS NULL
            OR (association_type_id <> '' AND association_type_id = btrim(association_type_id))
        ),
    CONSTRAINT chk_line_item_watch_change_signal_association_category
        CHECK (
            association_category IS NULL
            OR (association_category <> '' AND association_category = btrim(association_category))
        ),
    CONSTRAINT chk_line_item_watch_change_signal_shape
        CHECK (
            (signal_type IN ('CREATED', 'DELETED')
                AND property_name IS NULL
                AND property_value IS NULL
                AND external_deal_id IS NULL
                AND association_action IS NULL
                AND association_type_id IS NULL
                AND association_category IS NULL)
            OR
            (signal_type = 'PROPERTY_CHANGED'
                AND property_name IS NOT NULL
                AND property_value IS NOT NULL
                AND external_deal_id IS NULL
                AND association_action IS NULL
                AND association_type_id IS NULL
                AND association_category IS NULL)
            OR
            (signal_type = 'ASSOCIATION_CHANGED'
                AND property_name IS NULL
                AND property_value IS NULL
                AND external_deal_id IS NOT NULL
                AND association_action IS NOT NULL
                AND association_type_id IS NOT NULL
                AND association_category IS NOT NULL)
        )
);

CREATE INDEX idx_line_item_watch_change_signal_occurrence
    ON line_item_watch_change_signal (tenant_id, connection_id, occurred_at, id);
--rollback DROP TABLE line_item_watch_change_signal;
