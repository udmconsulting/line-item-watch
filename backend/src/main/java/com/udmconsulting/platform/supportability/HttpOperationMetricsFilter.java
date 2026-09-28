package com.udmconsulting.platform.supportability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class HttpOperationMetricsFilter extends OncePerRequestFilter {

    private final ApplicationOperationMetrics metrics;

    public HttpOperationMetricsFilter(ApplicationOperationMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        boolean threw = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            threw = true;
            throw exception;
        } finally {
            ApplicationOperation operation = RequestSupportability.operation(request);
            OperationalErrorCode errorCode = RequestSupportability.error(request);
            OperationOutcome outcome = outcome(response.getStatus(), threw);
            if (threw && errorCode == OperationalErrorCode.NONE) {
                errorCode = OperationalErrorCode.INTERNAL_ERROR;
            }
            metrics.record(operation, outcome, errorCode,
                    java.time.Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private static OperationOutcome outcome(int status, boolean threw) {
        if (threw || status >= 500) {
            return OperationOutcome.FAILED;
        }
        if (status >= 400) {
            return OperationOutcome.REJECTED;
        }
        return OperationOutcome.SUCCESS;
    }
}
