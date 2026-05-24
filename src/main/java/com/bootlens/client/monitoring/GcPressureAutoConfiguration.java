package com.bootlens.client.monitoring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(GcPressureProperties.class)
@ConditionalOnProperty(prefix = "bootlens.client.monitoring.gc-pressure", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GcPressureAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GcPressureMonitor gcPressureMonitor(GcPressureProperties properties) {
        return new GcPressureMonitor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public GcPressureInfoContributor gcPressureInfoContributor(GcPressureMonitor monitor) {
        return new GcPressureInfoContributor(monitor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "gcPressureMonitorLevelSource")
    public MonitorLevelSource gcPressureMonitorLevelSource(GcPressureMonitor monitor) {
        return new MonitorLevelSource() {
            public String name() { return "gcPressure"; }
            public MemoryLevel currentLevel() {
                GcSnapshot s = monitor.lastSnapshot();
                return s == null ? MemoryLevel.OK : monitor.classify(s.pausePercent());
            }
        };
    }
}
