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

    @Bean
    @ConditionalOnMissingBean(name = "memoryPressureMonitorLevelSource")
    public MonitorLevelSource memoryPressureMonitorLevelSource(MemoryPressureMonitor monitor) {
        return new MonitorLevelSource() {
            public String name() { return "memoryPressure"; }
            public MemoryLevel currentLevel() {
                MemorySnapshot s = monitor.lastSnapshot();
                if (s == null) return MemoryLevel.OK;
                double pct = s.containerPercent() != null ? s.containerPercent() : s.heapPercent();
                return monitor.classify(pct);
            }
        };
    }
}
