package com.udmconsulting.platform.activity.infrastructure.persistence;

import com.udmconsulting.platform.activity.application.ApplicationActivity;
import com.udmconsulting.platform.activity.application.ApplicationActivityAuditStore;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcApplicationActivityAuditStore implements ApplicationActivityAuditStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcApplicationActivityAuditStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(ApplicationActivity activity) {
        jdbcTemplate.update("""
                INSERT INTO application_activity_audit (
                    id, tenant_id, connection_id, occurred_at,
                    actor_type, actor_source, actor_reference,
                    action, resource_type, resource_reference,
                    previous_state, resulting_state, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                activity.id(),
                activity.tenantId().value(),
                activity.connectionId() == null ? null : activity.connectionId().value(),
                Timestamp.from(activity.occurredAt()),
                activity.actor().type().name(),
                activity.actor().source().name(),
                activity.actor().reference(),
                activity.action().name(),
                activity.resourceType().name(),
                activity.resourceReference(),
                activity.previousState(),
                activity.resultingState(),
                activity.correlationId());
    }
}
