package com.bootlens.client.monitoring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(DirectMemoryProperties.class)
@ConditionalOnProperty(prefix = "direct.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DirectMemoryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DirectMemoryMonitor directMemoryMonitor(DirectMemoryProperties properties) {
        return new DirectMemoryMonitor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public DirectMemoryInfoContributor directMemoryInfoContributor(DirectMemoryMonitor monitor) {
        return new DirectMemoryInfoContributor(monitor);
    }
}
