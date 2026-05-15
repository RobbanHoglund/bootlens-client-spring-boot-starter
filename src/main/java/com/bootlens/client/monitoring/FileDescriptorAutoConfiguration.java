package com.bootlens.client.monitoring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(FileDescriptorProperties.class)
@ConditionalOnProperty(prefix = "file.descriptors", name = "enabled", havingValue = "true", matchIfMissing = true)
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
}
