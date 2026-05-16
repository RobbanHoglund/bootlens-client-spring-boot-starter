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

    @Bean
    @ConditionalOnMissingBean(name = "threadDeadlockMonitorLevelSource")
    public MonitorLevelSource threadDeadlockMonitorLevelSource(ThreadDeadlockDetector detector) {
        return new MonitorLevelSource() {
            public String name() { return "threadDeadlock"; }
            public MemoryLevel currentLevel() {
                DeadlockSnapshot s = detector.lastSnapshot();
                if (s == null) return MemoryLevel.OK;
                return s.deadlocked() ? MemoryLevel.EMERGENCY : MemoryLevel.OK;
            }
        };
    }
}
