--liquibase formatted sql

--changeset udmconsulting:010-01-audit-credential-key-rewrap
ALTER TABLE application_activity_audit
    DROP CONSTRAINT chk_application_activity_audit_action,
    DROP CONSTRAINT chk_application_activity_audit_resource_type,
    DROP CONSTRAINT chk_application_activity_audit_shape,
    ADD CONSTRAINT chk_application_activity_audit_action
        CHECK (action IN (
            'PLATFORM_CONNECTION_ACTIVATED', 'PLATFORM_CONNECTION_REAUTHORIZED',
            'PLATFORM_CONNECTION_REAUTHENTICATION_REQUIRED',
            'PLATFORM_CONNECTION_DISCONNECTED', 'ENTITLEMENT_ACTIVATED',
            'ENTITLEMENT_DEACTIVATED', 'CREDENTIAL_KEY_REWRAPPED',
            'LINE_ITEM_SIGNAL_REQUEUED', 'LINE_ITEM_RECONCILIATION_REQUESTED',
            'LINE_ITEM_REPLAY_REQUESTED', 'LINE_ITEM_RETENTION_EXECUTED',
            'LINE_ITEM_FINDING_ACKNOWLEDGED', 'LINE_ITEM_GAP_ACKNOWLEDGED'
        )),
    ADD CONSTRAINT chk_application_activity_audit_resource_type
        CHECK (resource_type IN (
            'PLATFORM_CONNECTION', 'ENTITLEMENT', 'CREDENTIAL',
            'LINE_ITEM_WATCH_OPERATION'
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
            (resource_type = 'CREDENTIAL'
                AND action = 'CREDENTIAL_KEY_REWRAPPED'
                AND connection_id IS NOT NULL
                AND resource_reference = connection_id::text
                AND previous_state = 'PREVIOUS_KEY'
                AND resulting_state = 'ACTIVE_KEY')
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
