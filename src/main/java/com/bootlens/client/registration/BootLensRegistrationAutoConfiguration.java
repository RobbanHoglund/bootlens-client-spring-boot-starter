package com.bootlens.client.registration;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Auto-configuration for BootLens client registration and heartbeat support.
 */
@AutoConfiguration
@EnableConfigurationProperties(BootLensRegistrationProperties.class)
@ConditionalOnProperty(prefix = "bootlens.client.registration", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BootLensRegistrationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RegistrationTransport bootLensRegistrationTransport() {
        return new HttpRegistrationTransport();
    }

    @Bean("bootLensRegistrationClock")
    @ConditionalOnMissingBean(name = "bootLensRegistrationClock")
    Clock bootLensRegistrationClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    BootLensRegistrationClient bootLensRegistrationClient(
        BootLensRegistrationProperties properties,
        Environment environment,
        RegistrationTransport registrationTransport,
        @Qualifier("bootLensRegistrationClock") Clock clock
    ) {
        return new BootLensRegistrationClient(properties, environment, registrationTransport, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    BootLensRegistrationLifecycle bootLensRegistrationLifecycle(
        BootLensRegistrationProperties properties,
        BootLensRegistrationClient registrationClient
    ) {
        return new BootLensRegistrationLifecycle(properties, registrationClient);
    }
}
