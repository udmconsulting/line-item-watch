package com.udmconsulting.modules.lineitemwatch.application;

import com.udmconsulting.platform.runtime.ConditionalOnRuntimeRole;
import com.udmconsulting.platform.runtime.RuntimeRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ReliabilityProcessingProperties.class)
@ConditionalOnProperty(
        prefix = "line-item-watch.reliability", name = "enabled", havingValue = "true")
@ConditionalOnRuntimeRole({RuntimeRole.LOCAL, RuntimeRole.SERVICE})
class ReliabilityProcessingConfiguration {
}
