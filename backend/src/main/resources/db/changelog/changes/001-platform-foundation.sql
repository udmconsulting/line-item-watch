--liquibase formatted sql

--changeset udmconsulting:001-01-create-tenant
CREATE TABLE tenant (
    id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_tenant PRIMARY KEY (id)
);
--rollback DROP TABLE tenant;

--changeset udmconsulting:001-02-create-platform-connection
CREATE TABLE platform_connection (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_account_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_platform_connection PRIMARY KEY (id),
    CONSTRAINT fk_platform_connection_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE,
    CONSTRAINT uq_platform_connection_provider_account
        UNIQUE (provider, external_account_id),
    CONSTRAINT chk_platform_connection_provider
        CHECK (provider IN ('HUBSPOT')),
    CONSTRAINT chk_platform_connection_external_account_id
        CHECK (
            external_account_id <> ''
            AND external_account_id !~ '^[[:space:]]'
            AND external_account_id !~ '[[:space:]]$'
        )
);

CREATE INDEX idx_platform_connection_tenant_id
    ON platform_connection (tenant_id);
--rollback DROP TABLE platform_connection;

--changeset udmconsulting:001-03-create-tenant-entitlement
CREATE TABLE tenant_entitlement (
    tenant_id UUID NOT NULL,
    product_module VARCHAR(64) NOT NULL,
    enabled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_tenant_entitlement PRIMARY KEY (tenant_id, product_module),
    CONSTRAINT fk_tenant_entitlement_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE,
    CONSTRAINT chk_tenant_entitlement_product_module
        CHECK (product_module IN ('LINE_ITEM_WATCH'))
);
--rollback DROP TABLE tenant_entitlement;
