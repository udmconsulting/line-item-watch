package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DealAuditQueryFactoryTest {

    private final DealAuditCursorCodec codec = new DealAuditCursorCodec();
    private final DealAuditQueryFactory factory = new DealAuditQueryFactory(codec);

    @Test
    void appliesIndependentDefaultsLimitsAndCursors() {
        MockHttpServletRequest defaults = new MockHttpServletRequest();
        DealAuditQueryFactory.QueryInput defaultInput = factory.create("1001", defaults);
        assertThat(defaultInput.lineItemsLimit()).isEqualTo(10);
        assertThat(defaultInput.eventsLimit()).isEqualTo(20);

        ProviderObjectId deal = new ProviderObjectId("1001");
        String itemCursor = codec.encodeLineItems(deal, new ProviderObjectId("line-1"));
        byte[] key = new byte[32];
        String eventCursor = codec.encodeEvents(
                deal, new DealAuditViewDto.EventPosition(Instant.EPOCH, key));
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
}
