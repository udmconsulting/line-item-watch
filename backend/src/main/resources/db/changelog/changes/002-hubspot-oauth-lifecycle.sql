--liquibase formatted sql

--changeset udmconsulting:002-01-add-connection-lifecycle
ALTER TABLE platform_connection
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'DISCONNECTED',
    ADD COLUMN status_changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN credential_generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_platform_connection_status
        CHECK (status IN ('ACTIVE', 'REAUTH_REQUIRED', 'DISCONNECTED')),
    ADD CONSTRAINT chk_platform_connection_credential_generation
        CHECK (credential_generation >= 0);
--rollback ALTER TABLE platform_connection DROP CONSTRAINT chk_platform_connection_credential_generation; ALTER TABLE platform_connection DROP CONSTRAINT chk_platform_connection_status; ALTER TABLE platform_connection DROP COLUMN credential_generation; ALTER TABLE platform_connection DROP COLUMN status_changed_at; ALTER TABLE platform_connection DROP COLUMN status;

--changeset udmconsulting:002-02-create-oauth-install-state
CREATE TABLE oauth_install_state (
    state_hash BYTEA NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_oauth_install_state PRIMARY KEY (state_hash),
    CONSTRAINT uq_oauth_install_state_correlation UNIQUE (correlation_id),
    CONSTRAINT chk_oauth_install_state_hash_length CHECK (octet_length(state_hash) = 32),
    CONSTRAINT chk_oauth_install_state_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_oauth_install_state_consumed CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX idx_oauth_install_state_retention
    ON oauth_install_state (COALESCE(consumed_at, expires_at));
--rollback DROP TABLE oauth_install_state;

--changeset udmconsulting:002-03-create-connection-credential
CREATE TABLE connection_credential (
    connection_id UUID NOT NULL,
    cipher_version SMALLINT NOT NULL,
    key_id VARCHAR(128) NOT NULL,
    nonce BYTEA NOT NULL,
    ciphertext BYTEA NOT NULL,
    granted_scopes TEXT[] NOT NULL,
    credential_generation BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_connection_credential PRIMARY KEY (connection_id),
    CONSTRAINT fk_connection_credential_connection
        FOREIGN KEY (connection_id) REFERENCES platform_connection (id) ON DELETE CASCADE,
    CONSTRAINT chk_connection_credential_cipher_version CHECK (cipher_version = 1),
    CONSTRAINT chk_connection_credential_key_id
        CHECK (key_id <> '' AND key_id = btrim(key_id)),
    CONSTRAINT chk_connection_credential_nonce_length CHECK (octet_length(nonce) = 12),
    CONSTRAINT chk_connection_credential_ciphertext CHECK (octet_length(ciphertext) > 16),
    CONSTRAINT chk_connection_credential_scopes CHECK (
        cardinality(granted_scopes) > 0
        AND array_position(granted_scopes, NULL) IS NULL
        AND array_position(granted_scopes, '') IS NULL
    ),
    CONSTRAINT chk_connection_credential_generation CHECK (credential_generation > 0)
);
--rollback DROP TABLE connection_credential;
