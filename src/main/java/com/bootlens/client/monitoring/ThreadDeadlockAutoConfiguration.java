package com.bootlens.client.monitoring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ThreadDeadlockProperties.class)
@ConditionalOnProperty(prefix = "thread.deadlock", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ThreadDeadlockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ThreadDeadlockDetector threadDeadlockDetector(ThreadDeadlockProperties properties) {
        return new ThreadDeadlockDetector(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ThreadDeadlockInfoContributor threadDeadlockInfoContributor(ThreadDeadlockDetector detector) {
        return new ThreadDeadlockInfoContributor(detector);
    }
}
