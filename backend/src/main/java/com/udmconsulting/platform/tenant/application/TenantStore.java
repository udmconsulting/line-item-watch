package com.udmconsulting.platform.tenant.application;

import com.udmconsulting.platform.tenant.domain.Tenant;
import com.udmconsulting.platform.tenant.domain.TenantId;
import java.util.Optional;

public interface TenantStore {

    Tenant save(Tenant tenant);

    Optional<Tenant> findById(TenantId tenantId);
}
