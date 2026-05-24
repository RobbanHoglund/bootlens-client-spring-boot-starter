package com.bootlens.client.monitoring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(FileDescriptorProperties.class)
@ConditionalOnProperty(prefix = "bootlens.client.monitoring.file-descriptors", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FileDescriptorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FileDescriptorMonitor fileDescriptorMonitor(FileDescriptorProperties properties) {
        return new FileDescriptorMonitor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public FileDescriptorInfoContributor fileDescriptorInfoContributor(FileDescriptorMonitor monitor) {
        return new FileDescriptorInfoContributor(monitor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "fileDescriptorMonitorLevelSource")
    public MonitorLevelSource fileDescriptorMonitorLevelSource(FileDescriptorMonitor monitor) {
        return new MonitorLevelSource() {
            public String name() { return "fileDescriptors"; }
            public MemoryLevel currentLevel() {
                FileDescriptorSnapshot s = monitor.lastSnapshot();
                if (s == null || !s.available()) return MemoryLevel.OK;
                return monitor.classify(s.percent());
            }
        };
    }
}
