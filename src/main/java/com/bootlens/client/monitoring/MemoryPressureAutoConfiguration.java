package com.bootlens.client.monitoring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(MemoryPressureProperties.class)
@ConditionalOnProperty(prefix = "memory.pressure", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryPressureAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MemoryPressureMonitor memoryPressureMonitor(MemoryPressureProperties properties) {
        return new MemoryPressureMonitor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryPressureInfoContributor memoryPressureInfoContributor(MemoryPressureMonitor monitor) {
        return new MemoryPressureInfoContributor(monitor);
    }
}
