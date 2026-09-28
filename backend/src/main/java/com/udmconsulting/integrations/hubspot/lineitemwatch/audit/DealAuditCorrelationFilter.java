package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import com.udmconsulting.integrations.hubspot.config.HubSpotUiExtensionProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(prefix = "hubspot.ui-extension", name = "enabled", havingValue = "true")
final class DealAuditCorrelationFilter extends OncePerRequestFilter {

    static final String ATTRIBUTE = DealAuditCorrelationFilter.class.getName() + ".correlationId";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(HubSpotUiExtensionProperties.ENDPOINT_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        UUID correlationId = UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, correlationId);
        MDC.put("correlationId", correlationId.toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }

    static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof UUID id) {
            return id;
        }
        UUID generated = UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, generated);
        return generated;
    }
}
