package com.bootlens.client.monitoring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(LogErrorRateProperties.class)
@ConditionalOnProperty(prefix = "log.errors", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(name = "ch.qos.logback.classic.Logger")
public class LogErrorRateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LogErrorRateMonitor logErrorRateMonitor(LogErrorRateProperties properties) {
        return new LogErrorRateMonitor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public LogErrorRateInfoContributor logErrorRateInfoContributor(LogErrorRateMonitor monitor) {
        return new LogErrorRateInfoContributor(monitor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "logErrorRateMonitorLevelSource")
    public MonitorLevelSource logErrorRateMonitorLevelSource(LogErrorRateMonitor monitor) {
        return new MonitorLevelSource() {
            public String name() { return "logErrors"; }
            public MemoryLevel currentLevel() {
                LogErrorRateSnapshot s = monitor.lastSnapshot();
                return s == null ? MemoryLevel.OK : monitor.classify(s.errorsInInterval());
            }
        };
    }
}
