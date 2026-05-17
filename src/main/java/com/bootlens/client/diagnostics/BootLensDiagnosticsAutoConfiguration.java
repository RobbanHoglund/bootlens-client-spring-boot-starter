package com.bootlens.client.diagnostics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

/**
 * Auto-configuration for the BootLens diagnostics actuator endpoint and its
 * supporting services.
 */
@AutoConfiguration
@EnableConfigurationProperties(BootLensDiagnosticsProperties.class)
@ConditionalOnProperty(prefix = "bootlens.client.diagnostics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BootLensDiagnosticsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SecretSanitizer secretSanitizer(BootLensDiagnosticsProperties properties) {
        return new SecretSanitizer(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public HeapDumpManager heapDumpManager(BootLensDiagnosticsProperties properties) {
        return new HeapDumpManager(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public VmDiagnostics vmDiagnostics(
        SecretSanitizer secretSanitizer,
        BootLensDiagnosticsProperties properties,
        HeapDumpManager heapDumpManager
    ) {
        return new VmDiagnostics(secretSanitizer, properties, heapDumpManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "bootlens.client.diagnostics", name = "endpoint-enabled", havingValue = "true", matchIfMissing = true)
    public BootLensDiagnosticsEndpoint bootLensDiagnosticsEndpoint(
        BootLensDiagnosticsProperties properties,
        VmDiagnostics vmDiagnostics,
        HeapDumpManager heapDumpManager,
        ObjectProvider<BootLensHealthDiagnosticsService> healthDiagnosticsService
    ) {
        return new BootLensDiagnosticsEndpoint(properties, vmDiagnostics, heapDumpManager, healthDiagnosticsService.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.boot.health.registry.HealthContributorRegistry")
    @ConditionalOnProperty(prefix = "bootlens.client.diagnostics", name = "endpoint-enabled", havingValue = "true", matchIfMissing = true)
    public BootLensHealthDiagnosticsService bootLensHealthDiagnosticsService(
        BootLensDiagnosticsProperties properties,
        ObjectProvider<HealthContributorRegistry> healthContributorRegistry,
        ObjectProvider<HealthEndpointGroups> healthEndpointGroups
    ) {
        return new BootLensHealthDiagnosticsService(properties, healthContributorRegistry, healthEndpointGroups);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "bootlens.client.diagnostics", name = "endpoint-enabled", havingValue = "true", matchIfMissing = true)
    public BootLensDiagnosticsWebExtension bootLensDiagnosticsWebExtension(HeapDumpManager heapDumpManager) {
        return new BootLensDiagnosticsWebExtension(heapDumpManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "bootlens.client.diagnostics", name = "endpoint-timing-header-enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<BootLensEndpointTimingFilter> bootLensEndpointTimingFilter(
        BootLensDiagnosticsProperties properties,
        ObjectProvider<WebEndpointProperties> webEndpointProperties
    ) {
        FilterRegistrationBean<BootLensEndpointTimingFilter> registration = new FilterRegistrationBean<>(
            new BootLensEndpointTimingFilter(properties, webEndpointProperties.getIfAvailable()));
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 20);
        return registration;
    }
}
