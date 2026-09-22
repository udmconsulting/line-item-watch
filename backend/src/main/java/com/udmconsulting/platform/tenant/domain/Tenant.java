package com.udmconsulting.platform.tenant.domain;

import java.util.Objects;

public record Tenant(TenantId id) {

    public Tenant {
        Objects.requireNonNull(id, "id must not be null");
    }
}
