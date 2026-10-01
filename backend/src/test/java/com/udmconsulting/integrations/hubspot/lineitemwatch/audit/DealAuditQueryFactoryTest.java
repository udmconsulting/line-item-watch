package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.modules.lineitemwatch.application.DealAuditQuery;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DealAuditQueryFactoryTest {

    private final DealAuditCursorCodec codec = DealAuditCursorCodec.forTests();
    private final DealAuditQueryFactory factory = new DealAuditQueryFactory(codec);

    @Test
    void appliesIndependentDefaultsLimitsAndCursors() {
        MockHttpServletRequest defaults = new MockHttpServletRequest();
        DealAuditQueryFactory.QueryInput defaultInput = factory.create("1001", defaults);
        assertThat(defaultInput.lineItemsLimit()).isEqualTo(10);
        assertThat(defaultInput.eventsLimit()).isEqualTo(20);

        ProviderObjectId deal = new ProviderObjectId("1001");
        String itemCursor = codec.encodeLineItems(
                deal, DealAuditQuery.LineItemFilter.none(), new ProviderObjectId("line-1"));
        byte[] key = new byte[32];
        String eventCursor = codec.encodeEvents(
                deal,
                DealAuditQuery.EventFilter.none(),
                new DealAuditViewDto.EventPosition(Instant.EPOCH, key));
        MockHttpServletRequest paged = new MockHttpServletRequest();
        paged.addParameter("lineItemsLimit", "0");
        paged.addParameter("eventsLimit", "1");
        paged.addParameter("lineItemsCursor", itemCursor);
        paged.addParameter("eventsCursor", eventCursor);

        DealAuditQueryFactory.QueryInput result = factory.create("1001", paged);

        assertThat(result.lineItemsLimit()).isZero();
        assertThat(result.eventsLimit()).isOne();
        assertThat(result.lineItemsCursor().lineItemId().value()).isEqualTo("line-1");
        assertThat(result.eventsCursor().semanticKey()).containsExactly(key);
    }

    @Test
    void normalizesSearchAndBuildsAllowlistedConjunctiveEventFilters() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("lineItemSearch", "  Árvíz %_\\  ");
        request.addParameter("field", "quantity");
        request.addParameter("lineItemId", "2002");
        request.addParameter("from", "2026-03-29T00:00:00Z");
        request.addParameter("to", "2026-03-30T00:00:00Z");

        DealAuditQueryFactory.QueryInput result = factory.create("1001", request);

        assertThat(result.lineItemFilter().search()).isEqualTo("Árvíz %_\\");
        assertThat(result.eventFilter().eventType())
                .isEqualTo(LineItemAuditType.PROPERTY_CHANGED);
        assertThat(result.eventFilter().field())
                .isEqualTo(MonitoredLineItemProperty.QUANTITY);
        assertThat(result.eventFilter().lineItemId().value()).isEqualTo("2002");
        assertThat(result.eventFilter().from())
                .isEqualTo(Instant.parse("2026-03-29T00:00:00Z"));
        assertThat(result.eventFilter().to())
                .isEqualTo(Instant.parse("2026-03-30T00:00:00Z"));
    }

    @Test
    void acceptsUnicodeCodePointBoundsAndTreatsBlankSearchAsNoFilter() {
        MockHttpServletRequest minimum = new MockHttpServletRequest();
        minimum.addParameter("lineItemSearch", "🦄x");
        assertThat(factory.create("1001", minimum).lineItemFilter().search()).isEqualTo("🦄x");

        MockHttpServletRequest maximum = new MockHttpServletRequest();
        maximum.addParameter("lineItemSearch", "🦄".repeat(100));
        assertThat(factory.create("1001", maximum).lineItemFilter().search())
                .hasSize(200)
                .satisfies(value -> assertThat(value.codePointCount(0, value.length())).isEqualTo(100));

        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.addParameter("lineItemSearch", " \t ");
        assertThat(factory.create("1001", blank).lineItemFilter().active()).isFalse();
    }

    @Test
    void rejectsInvalidFilterSyntaxCombinationsAndCursorContextMismatch() {
        assertInvalid("lineItemSearch", "x");
        assertInvalid("lineItemSearch", "x".repeat(101));
        assertInvalid("eventType", "UNKNOWN");
        assertInvalid("field", "hs_secret");
        assertInvalid("lineItemId", "0");
        assertInvalid("lineItemId", "line-1");
        assertInvalid("from", "2026-09-01");

        MockHttpServletRequest incompatible = new MockHttpServletRequest();
        incompatible.addParameter("eventType", "CREATED");
        incompatible.addParameter("field", "quantity");
        assertThatThrownBy(() -> factory.create("1001", incompatible))
                .isExactlyInstanceOf(DealAuditRequestException.class);

        MockHttpServletRequest reversed = new MockHttpServletRequest();
        reversed.addParameter("from", "2026-10-01T00:00:00Z");
        reversed.addParameter("to", "2026-09-01T00:00:00Z");
        assertThatThrownBy(() -> factory.create("1001", reversed))
                .isExactlyInstanceOf(DealAuditRequestException.class);

        ProviderObjectId deal = new ProviderObjectId("1001");
        String cursor = codec.encodeLineItems(
                deal,
                new DealAuditQuery.LineItemFilter("support"),
                new ProviderObjectId("2002"));
        MockHttpServletRequest mismatched = new MockHttpServletRequest();
        mismatched.addParameter("lineItemSearch", "other");
        mismatched.addParameter("lineItemsCursor", cursor);
        assertThatThrownBy(() -> factory.create("1001", mismatched))
                .isExactlyInstanceOf(DealAuditRequestException.class);
    }

    @Test
    void rejectsInvalidDealLimitsSkippedResponseAndDuplicateParameter() {
        assertThatThrownBy(() -> factory.create("../other", new MockHttpServletRequest()))
                .isInstanceOf(DealAuditRequestException.class);

        MockHttpServletRequest oversized = new MockHttpServletRequest();
        oversized.addParameter("lineItemsLimit", "21");
        assertThatThrownBy(() -> factory.create("1001", oversized))
                .isInstanceOf(DealAuditRequestException.class);

        MockHttpServletRequest skipped = new MockHttpServletRequest();
        skipped.addParameter("lineItemsLimit", "0");
        skipped.addParameter("eventsLimit", "0");
        assertThatThrownBy(() -> factory.create("1001", skipped))
                .isInstanceOf(DealAuditRequestException.class);

        MockHttpServletRequest duplicate = new MockHttpServletRequest();
        duplicate.addParameter("eventsLimit", "1", "2");
        assertThatThrownBy(() -> factory.create("1001", duplicate))
                .isInstanceOf(DealAuditRequestException.class);
    }

    private void assertInvalid(String name, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter(name, value);
        assertThatThrownBy(() -> factory.create("1001", request))
                .isExactlyInstanceOf(DealAuditRequestException.class);
    }
}
