package com.bootlens.client.monitoring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(MetaspaceProperties.class)
@ConditionalOnProperty(prefix = "metaspace", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MetaspaceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetaspaceMonitor metaspaceMonitor(MetaspaceProperties properties) {
        return new MetaspaceMonitor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MetaspaceInfoContributor metaspaceInfoContributor(MetaspaceMonitor monitor) {
        return new MetaspaceInfoContributor(monitor);
    }
}
