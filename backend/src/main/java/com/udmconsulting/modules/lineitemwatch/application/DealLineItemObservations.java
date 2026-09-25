package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.domain.LineItemObservation;
import com.udmconsulting.modules.lineitemwatch.domain.ProviderObjectId;
import java.util.List;
import java.util.Objects;

public record DealLineItemObservations(
        ProviderObjectId dealId, List<LineItemObservation> lineItems) {

    public DealLineItemObservations {
        Objects.requireNonNull(dealId, "dealId must not be null");
        Objects.requireNonNull(lineItems, "lineItems must not be null");
        if (lineItems.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("lineItems must not contain null");
        }
        lineItems = List.copyOf(lineItems);
    }
}
