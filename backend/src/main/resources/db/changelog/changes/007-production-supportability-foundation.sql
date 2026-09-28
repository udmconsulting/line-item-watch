--liquibase formatted sql

--changeset udmconsulting:007-01-create-application-activity-audit
CREATE TABLE application_activity_audit (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    connection_id UUID,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_type VARCHAR(32) NOT NULL,
    actor_source VARCHAR(32) NOT NULL,
    actor_reference VARCHAR(255),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_reference VARCHAR(64) NOT NULL,
    previous_state VARCHAR(32) NOT NULL,
    resulting_state VARCHAR(32) NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT pk_application_activity_audit PRIMARY KEY (id),
    CONSTRAINT fk_application_activity_audit_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE,
    CONSTRAINT fk_application_activity_audit_connection
        FOREIGN KEY (tenant_id, connection_id)
        REFERENCES platform_connection (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_application_activity_audit_actor_type
        CHECK (actor_type IN ('USER', 'SYSTEM', 'PROVIDER', 'OPERATOR', 'UNATTRIBUTED')),
    CONSTRAINT chk_application_activity_audit_actor_source
        CHECK (
            actor_source IN ('APPLICATION', 'HUBSPOT')
            AND (actor_type <> 'SYSTEM' OR actor_source = 'APPLICATION')
            AND (actor_type <> 'PROVIDER' OR actor_source = 'HUBSPOT')
        ),
    CONSTRAINT chk_application_activity_audit_actor_reference
        CHECK (
            (actor_type IN ('USER', 'OPERATOR') AND actor_reference IS NOT NULL
                AND btrim(actor_reference) <> '')
            OR
            (actor_type IN ('SYSTEM', 'UNATTRIBUTED') AND actor_reference IS NULL)
            OR
            (actor_type = 'PROVIDER'
                AND (actor_reference IS NULL OR btrim(actor_reference) <> ''))
        ),
    CONSTRAINT chk_application_activity_audit_action
        CHECK (action IN (
            'PLATFORM_CONNECTION_ACTIVATED',
            'PLATFORM_CONNECTION_REAUTHORIZED',
            'PLATFORM_CONNECTION_REAUTHENTICATION_REQUIRED',
            'PLATFORM_CONNECTION_DISCONNECTED',
            'ENTITLEMENT_ACTIVATED',
            'ENTITLEMENT_DEACTIVATED'
        )),
    CONSTRAINT chk_application_activity_audit_resource_type
        CHECK (resource_type IN ('PLATFORM_CONNECTION', 'ENTITLEMENT')),
    CONSTRAINT chk_application_activity_audit_shape
        CHECK (
            (
                action IN (
                    'PLATFORM_CONNECTION_ACTIVATED',
                    'PLATFORM_CONNECTION_REAUTHORIZED',
                    'PLATFORM_CONNECTION_REAUTHENTICATION_REQUIRED',
                    'PLATFORM_CONNECTION_DISCONNECTED'
                )
                AND resource_type = 'PLATFORM_CONNECTION'
                AND connection_id IS NOT NULL
                AND resource_reference = connection_id::text
                AND previous_state IN ('ACTIVE', 'REAUTH_REQUIRED', 'DISCONNECTED')
                AND resulting_state IN ('ACTIVE', 'REAUTH_REQUIRED', 'DISCONNECTED')
                AND (
                    (action = 'PLATFORM_CONNECTION_ACTIVATED'
                        AND previous_state IN ('REAUTH_REQUIRED', 'DISCONNECTED')
                        AND resulting_state = 'ACTIVE')
                    OR
                    (action = 'PLATFORM_CONNECTION_REAUTHORIZED'
                        AND previous_state = 'ACTIVE'
                        AND resulting_state = 'ACTIVE')
                    OR
                    (action = 'PLATFORM_CONNECTION_REAUTHENTICATION_REQUIRED'
                        AND previous_state = 'ACTIVE'
                        AND resulting_state = 'REAUTH_REQUIRED')
                    OR
                    (action = 'PLATFORM_CONNECTION_DISCONNECTED'
                        AND previous_state IN ('ACTIVE', 'REAUTH_REQUIRED')
                        AND resulting_state = 'DISCONNECTED')
                )
            )
            OR
            (
                action IN ('ENTITLEMENT_ACTIVATED', 'ENTITLEMENT_DEACTIVATED')
                AND resource_type = 'ENTITLEMENT'
                AND connection_id IS NULL
                AND resource_reference = 'LINE_ITEM_WATCH'
                AND previous_state IN ('ENABLED', 'DISABLED')
                AND resulting_state IN ('ENABLED', 'DISABLED')
                AND (
                    (action = 'ENTITLEMENT_ACTIVATED'
                        AND previous_state = 'DISABLED' AND resulting_state = 'ENABLED')
                    OR
                    (action = 'ENTITLEMENT_DEACTIVATED'
                        AND previous_state = 'ENABLED' AND resulting_state = 'DISABLED')
                )
            )
        )
);

CREATE INDEX idx_application_activity_audit_tenant_time
    ON application_activity_audit (tenant_id, occurred_at DESC, id DESC);

CREATE INDEX idx_application_activity_audit_connection_time
    ON application_activity_audit (tenant_id, connection_id, occurred_at DESC, id DESC)
    WHERE connection_id IS NOT NULL;
--rollback DROP TABLE application_activity_audit;
