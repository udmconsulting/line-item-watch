package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.modules.lineitemwatch.domain.LineItemChangeSignal;
import com.udmconsulting.platform.connection.domain.PlatformConnectionId;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.List;

public interface LineItemChangeSignalStore {

    CaptureResult capture(
            TenantId tenantId,
            PlatformConnectionId connectionId,
            List<LineItemChangeSignal> signals);

    record CaptureResult(int captured, int duplicates, boolean eligible) {

        public static CaptureResult ineligible() {
            return new CaptureResult(0, 0, false);
        }
    }
}
