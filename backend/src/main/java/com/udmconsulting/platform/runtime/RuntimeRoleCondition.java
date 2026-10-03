package com.udmconsulting.platform.runtime;

import java.util.Locale;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class RuntimeRoleCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var attributes = metadata.getAnnotationAttributes(
                ConditionalOnRuntimeRole.class.getName(), true);
        if (attributes == null) {
            return false;
        }
        RuntimeRole configured = configuredRole(context);
        RuntimeRole[] allowed = (RuntimeRole[]) attributes.get("value");
        for (RuntimeRole role : allowed) {
            if (role == configured) {
                return true;
            }
        }
        return false;
    }

    private static RuntimeRole configuredRole(ConditionContext context) {
        String configured = context.getEnvironment()
                .getProperty("application.runtime.role", RuntimeRole.LOCAL.name());
        try {
            return RuntimeRole.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unsupported application runtime role", exception);
        }
    }
}
