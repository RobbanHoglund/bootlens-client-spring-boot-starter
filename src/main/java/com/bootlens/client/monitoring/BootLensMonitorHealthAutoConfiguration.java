package com.bootlens.client.monitoring;

import java.util.List;

import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(HealthIndicator.class)
@EnableConfigurationProperties(BootLensMonitorHealthProperties.class)
@ConditionalOnProperty(prefix = "bootlens.client.health", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BootLensMonitorHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BootLensMonitorHealthIndicator bootLensMonitorHealthIndicator(List<MonitorLevelSource> sources) {
        return new BootLensMonitorHealthIndicator(sources);
    }
}
