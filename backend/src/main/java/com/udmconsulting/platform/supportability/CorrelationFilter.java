package com.udmconsulting.platform.supportability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    private static final String ATTRIBUTE = CorrelationFilter.class.getName() + ".correlationId";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        UUID correlationId = correlationId(request);
        response.setHeader(HEADER, correlationId.toString());
        try (DiagnosticContext.Scope ignored = DiagnosticContext.withCorrelationId(correlationId)) {
            filterChain.doFilter(request, response);
        }
    }

    public static UUID correlationId(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof UUID id) {
            return id;
        }
        UUID generated = UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, generated);
        return generated;
    }
}
