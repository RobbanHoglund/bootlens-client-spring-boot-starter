package com.bootlens.client.diagnostics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnProperty(
    prefix = "bootlens.client.diagnostics",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class BootLensClientInfoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BootLensClientVersionInfoContributor bootLensClientVersionInfoContributor() {
        return new BootLensClientVersionInfoContributor();
    }

    @Bean
    @ConditionalOnMissingBean
    public BootLensPortInfoContributor bootLensPortInfoContributor(Environment environment) {
        return new BootLensPortInfoContributor(environment);
    }
}
