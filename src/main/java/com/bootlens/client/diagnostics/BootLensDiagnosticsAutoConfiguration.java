package com.bootlens.client.diagnostics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
        HeapDumpManager heapDumpManager
    ) {
        return new BootLensDiagnosticsEndpoint(properties, vmDiagnostics, heapDumpManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "bootlens.client.diagnostics", name = "endpoint-enabled", havingValue = "true", matchIfMissing = true)
    public BootLensDiagnosticsWebExtension bootLensDiagnosticsWebExtension(HeapDumpManager heapDumpManager) {
        return new BootLensDiagnosticsWebExtension(heapDumpManager);
    }
}
