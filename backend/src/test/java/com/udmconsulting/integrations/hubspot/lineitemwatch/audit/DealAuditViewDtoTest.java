package com.udmconsulting.integrations.hubspot.lineitemwatch.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.udmconsulting.modules.lineitemwatch.application.DealAuditView;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemAuditType;
import com.udmconsulting.modules.lineitemwatch.domain.LineItemHistoryCoverage;
import com.udmconsulting.modules.lineitemwatch.domain.MonitoredLineItemProperty;
import com.udmconsulting.modules.lineitemwatch.domain.ObservedValue;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class DealAuditViewDtoTest {

    private static final Instant TIME = Instant.parse("2026-09-28T12:00:00Z");
    private final DealAuditCursorCodec codec = new DealAuditCursorCodec();
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void serializesEveryStablePropertyStateCoverageAndIndependentCursors() throws Exception {
        EnumMap<MonitoredLineItemProperty, ObservedValue> latest = allAbsent();
        latest.put(MonitoredLineItemProperty.NAME, ObservedValue.value("A"));
        latest.put(MonitoredLineItemProperty.PRICE, ObservedValue.unknown());
        DealAuditView.LineItemSummary summary = new DealAuditView.LineItemSummary(
                new ProviderObjectId("line-1"),
                false,
                null,
                true,
                DealAuditView.Membership.PRESENT,
                null,
                latest,
                new DealAuditView.HistoryCoverage(
                        LineItemHistoryCoverage.Mode.SIGNAL_FIRST, TIME, true));
        byte[] semanticKey = new byte[32];
        semanticKey[31] = 1;
        DealAuditView.AuditEvent event = new DealAuditView.AuditEvent(
                semanticKey,
                new ProviderObjectId("line-1"),
                LineItemAuditType.PROPERTY_CHANGED,
                TIME,
                MonitoredLineItemProperty.NAME,
                ObservedValue.unknown(),
                ObservedValue.value("A"));
        DealAuditQueryFactory.QueryInput input = new DealAuditQueryFactory.QueryInput(
                new ProviderObjectId("1001"), 1, null, 1, null);

        DealAuditViewDto dto = DealAuditViewDto.from(
                input,
                new DealAuditView(
                        new DealAuditView.Page<>(List.of(summary), true),
                        new DealAuditView.Page<>(List.of(event), true)),
                codec);
        String json = objectMapper.writeValueAsString(dto);

        assertThat(json)
                .contains("\"mode\":\"SIGNAL_FIRST\"")
                .contains("\"hasUnknownState\":true")
                .contains("\"unitPrice\":{\"state\":\"UNKNOWN\",\"value\":null")
                .contains("\"unitDiscount\":{\"state\":\"ABSENT\",\"value\":null")
                .contains("\"name\":{\"state\":\"VALUE\",\"value\":\"A\"")
                .contains("\"nextCursor\":")
                .doesNotContain("COMPLETE")
                .doesNotContain("tenantId", "connectionId", "semanticKey", "signal");
        assertThat(dto.events().items().getFirst().eventId())
                .startsWith("evt_")
                .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(semanticKey))
                .isEqualTo(DealAuditViewDto.from(
                        input,
                        new DealAuditView(
                                new DealAuditView.Page<>(List.of(summary), true),
                                new DealAuditView.Page<>(List.of(event), true)),
                        codec).events().items().getFirst().eventId());
        assertThat(summary.latest()).hasSize(MonitoredLineItemProperty.values().length);
    }

    @Test
    void truncatesByUnicodeCodePointWithoutSplittingSurrogatePairs() {
        String source = "x".repeat(511) + "🦄" + "tail";

        DealAuditViewDto.Value value = DealAuditViewDto.Value.from(ObservedValue.value(source));

        assertThat(value.truncated()).isTrue();
        assertThat(value.value()).hasSize(513);
        assertThat(value.value().codePointCount(0, value.value().length())).isEqualTo(512);
        assertThat(value.value()).endsWith("🦄");
    }

    @Test
    void maximumTwoSectionPageRemainsBoundedAfterValueTruncation() throws Exception {
        String oversized = "🦄".repeat(600);
        EnumMap<MonitoredLineItemProperty, ObservedValue> latest = allAbsent();
        latest.replaceAll((property, ignored) -> ObservedValue.value(oversized));
        List<DealAuditView.LineItemSummary> items = IntStream.range(0, 20)
                .mapToObj(index -> new DealAuditView.LineItemSummary(
                        new ProviderObjectId("line-" + index),
                        false,
                        null,
                        true,
                        DealAuditView.Membership.PRESENT,
                        null,
                        latest,
                        new DealAuditView.HistoryCoverage(
                                LineItemHistoryCoverage.Mode.SIGNAL_FIRST, TIME, false)))
                .toList();
        List<DealAuditView.AuditEvent> events = IntStream.range(0, 20)
                .mapToObj(index -> {
                    byte[] key = new byte[32];
                    key[31] = (byte) index;
                    return new DealAuditView.AuditEvent(
                            key,
                            new ProviderObjectId("line-" + index),
                            LineItemAuditType.PROPERTY_CHANGED,
                            TIME.minusSeconds(index),
                            MonitoredLineItemProperty.NAME,
                            ObservedValue.value(oversized),
                            ObservedValue.value(oversized));
                })
                .toList();

        byte[] payload = objectMapper.writeValueAsBytes(DealAuditViewDto.from(
                new DealAuditQueryFactory.QueryInput(
                        new ProviderObjectId("1001"), 20, null, 20, null),
                new DealAuditView(
                        new DealAuditView.Page<>(items, false),
                        new DealAuditView.Page<>(events, false)),
                codec));

        assertThat(payload.length).isLessThan(750_000);
    }

    private static EnumMap<MonitoredLineItemProperty, ObservedValue> allAbsent() {
        EnumMap<MonitoredLineItemProperty, ObservedValue> values =
                new EnumMap<>(MonitoredLineItemProperty.class);
        for (MonitoredLineItemProperty property : MonitoredLineItemProperty.values()) {
            values.put(property, ObservedValue.absent());
        }
        return values;
    }
}
