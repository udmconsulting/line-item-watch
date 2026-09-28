package com.udmconsulting.platform.supportability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationFilterTest {

    private final CorrelationFilter filter = new CorrelationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesServerOwnedHeaderAndRestoresPriorMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        request.addHeader(CorrelationFilter.HEADER, "caller-controlled");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("existing", "preserved");
        List<String> during = new ArrayList<>();

        filter.doFilter(request, response, (currentRequest, currentResponse) -> {
            during.add(MDC.get(DiagnosticContext.CORRELATION_ID));
            assertThat(MDC.get("existing")).isEqualTo("preserved");
        });

        assertThat(during).hasSize(1);
        assertThat(UUID.fromString(during.getFirst())).isNotNull();
        assertThat(response.getHeader(CorrelationFilter.HEADER)).isEqualTo(during.getFirst());
        assertThat(response.getHeader(CorrelationFilter.HEADER)).isNotEqualTo("caller-controlled");
        assertThat(MDC.get(DiagnosticContext.CORRELATION_ID)).isNull();
        assertThat(MDC.get("existing")).isEqualTo("preserved");
    }

    @Test
    void errorAndAsyncRedispatchesReuseRequestCorrelationWithoutMdcLeakage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        List<String> observed = new ArrayList<>();

        dispatch(request, DispatcherType.REQUEST, observed);
        dispatch(request, DispatcherType.ERROR, observed);
        dispatch(request, DispatcherType.ASYNC, observed);

        assertThat(observed).hasSize(3).allMatch(observed.getFirst()::equals);
        assertThat(MDC.get(DiagnosticContext.CORRELATION_ID)).isNull();
    }

    @Test
    void separateRequestsReceiveSeparateCorrelationIds() throws Exception {
        List<String> observed = new ArrayList<>();
        dispatch(new MockHttpServletRequest("GET", "/one"), DispatcherType.REQUEST, observed);
        dispatch(new MockHttpServletRequest("GET", "/two"), DispatcherType.REQUEST, observed);
        assertThat(observed).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void nestedDiagnosticScopesRestoreTheCompletePreviousContext() {
        UUID outerCorrelation = UUID.randomUUID();
        UUID innerOperation = UUID.randomUUID();
        MDC.put("existing", "preserved");

        try (DiagnosticContext.Scope outer = DiagnosticContext.withCorrelationId(outerCorrelation)) {
            MDC.put("insideOuter", "outer-value");
            try (DiagnosticContext.Scope inner = DiagnosticContext.withOperationId(innerOperation)) {
                MDC.put("insideInner", "inner-value");
                assertThat(MDC.getCopyOfContextMap()).containsAllEntriesOf(java.util.Map.of(
                        "existing", "preserved",
                        "insideOuter", "outer-value",
                        DiagnosticContext.CORRELATION_ID, outerCorrelation.toString(),
                        DiagnosticContext.OPERATION_ID, innerOperation.toString(),
                        "insideInner", "inner-value"));
            }
            assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                    "existing", "preserved",
                    "insideOuter", "outer-value",
                    DiagnosticContext.CORRELATION_ID, outerCorrelation.toString()));
        }

        assertThat(MDC.getCopyOfContextMap())
                .containsExactlyEntriesOf(java.util.Map.of("existing", "preserved"));
    }

    private void dispatch(
            MockHttpServletRequest request,
            DispatcherType dispatcherType,
            List<String> observed) throws Exception {
        request.setDispatcherType(dispatcherType);
        filter.doFilter(request, new MockHttpServletResponse(),
                (currentRequest, currentResponse) ->
                        observed.add(MDC.get(DiagnosticContext.CORRELATION_ID)));
    }
}
