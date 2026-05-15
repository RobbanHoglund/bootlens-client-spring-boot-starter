package com.bootlens.client.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class RailwayRegistrationEnvironmentPostProcessorTest {

    private final RailwayRegistrationEnvironmentPostProcessor processor =
        new RailwayRegistrationEnvironmentPostProcessor();

    // --- toKebabCase ---

    @Test
    void kebabCaseLowercasesInput() {
        assertThat(RailwayRegistrationEnvironmentPostProcessor.toKebabCase("TradingBot"))
            .isEqualTo("tradingbot");
    }

    @Test
    void kebabCaseReplacesSpacesWithHyphens() {
        assertThat(RailwayRegistrationEnvironmentPostProcessor.toKebabCase("Trading Bot"))
            .isEqualTo("trading-bot");
    }

    @Test
    void kebabCaseReplacesUnderscoresWithHyphens() {
        assertThat(RailwayRegistrationEnvironmentPostProcessor.toKebabCase("trading_bot"))
            .isEqualTo("trading-bot");
    }

    @Test
    void kebabCaseCollapsesMixedSeparators() {
        assertThat(RailwayRegistrationEnvironmentPostProcessor.toKebabCase("My  Trading--Bot"))
            .isEqualTo("my-trading-bot");
    }

    @Test
    void kebabCasePassesThroughAlreadyKebabInput() {
        assertThat(RailwayRegistrationEnvironmentPostProcessor.toKebabCase("trading-bot"))
            .isEqualTo("trading-bot");
    }

    // --- postProcessEnvironment: no-op when Railway is absent ---

    @Test
    void doesNothingWhenRailwayServiceNameAbsent() {
        StandardEnvironment env = new StandardEnvironment();

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.app-name")).isNull();
        assertThat(env.getProperty("bootlens.client.registration.instance-id")).isNull();
    }

    // --- postProcessEnvironment: core derived properties ---

    @Test
    void derivesAppNameAndAppIdFromServiceName() {
        StandardEnvironment env = envWithRailway("Trading Bot", "production");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.app-name")).isEqualTo("Trading Bot");
        assertThat(env.getProperty("bootlens.client.registration.app-id")).isEqualTo("trading-bot");
    }

    @Test
    void derivesInstanceIdFromAppIdAndHostname() {
        StandardEnvironment env = envWithRailway("trading-bot", "production");
        addVars(env, Map.of("HOSTNAME", "abc123xyz"));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.instance-id"))
            .isEqualTo("trading-bot-abc123xyz");
    }

    @Test
    void derivesEnvironmentFromRailwayEnvironmentName() {
        StandardEnvironment env = envWithRailway("my-service", "staging");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.environment")).isEqualTo("staging");
    }

    @Test
    void defaultsEnvironmentToProductionWhenNotSet() {
        StandardEnvironment env = new StandardEnvironment();
        addVars(env, Map.of("RAILWAY_SERVICE_NAME", "my-service"));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.environment")).isEqualTo("production");
    }

    @Test
    void derivesRegionFromRailwayRegion() {
        StandardEnvironment env = envWithRailway("my-service", "production");
        addVars(env, Map.of("RAILWAY_REGION", "europe-west4"));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.region")).isEqualTo("europe-west4");
    }

    // --- URL derivation ---

    @Test
    void derivesBaseUrlFromPublicDomain() {
        StandardEnvironment env = envWithRailway("my-service", "production");
        addVars(env, Map.of("RAILWAY_PUBLIC_DOMAIN", "my-service.up.railway.app"));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.base-url"))
            .isEqualTo("https://my-service.up.railway.app");
    }

    @Test
    void prefersPrivateDomainForActuatorUrl() {
        StandardEnvironment env = envWithRailway("my-service", "production");
        addVars(env, Map.of(
            "RAILWAY_PRIVATE_DOMAIN", "my-service.railway.internal",
            "RAILWAY_PUBLIC_DOMAIN",  "my-service.up.railway.app",
            "PORT",                   "3000"
        ));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.actuator-base-url"))
            .isEqualTo("http://my-service.railway.internal:3000/actuator");
    }

    @Test
    void fallsBackToPublicDomainForActuatorUrlWhenPrivateAbsent() {
        StandardEnvironment env = envWithRailway("my-service", "production");
        addVars(env, Map.of("RAILWAY_PUBLIC_DOMAIN", "my-service.up.railway.app"));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.actuator-base-url"))
            .isEqualTo("https://my-service.up.railway.app/actuator");
    }

    @Test
    void noUrlsSetWhenDomainsAbsent() {
        StandardEnvironment env = envWithRailway("my-service", "production");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.base-url")).isNull();
        assertThat(env.getProperty("bootlens.client.registration.actuator-base-url")).isNull();
    }

    // --- Labels ---

    @Test
    void addsRailwayLabels() {
        StandardEnvironment env = envWithRailway("trading-bot", "production");
        addVars(env, Map.of("RAILWAY_PROJECT_NAME", "acme-platform"));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.labels.railway-service"))
            .isEqualTo("trading-bot");
        assertThat(env.getProperty("bootlens.client.registration.labels.railway-environment"))
            .isEqualTo("production");
        assertThat(env.getProperty("bootlens.client.registration.labels.railway-project"))
            .isEqualTo("acme-platform");
    }

    // --- Explicit config takes precedence over Railway defaults ---

    @Test
    void explicitPropertyTakesPrecedenceOverRailwayDefault() {
        StandardEnvironment env = envWithRailway("trading-bot", "production");
        // Simulate explicit application.properties overrides at higher priority
        env.getPropertySources().addFirst(new MapPropertySource("application",
            Map.of("bootlens.client.registration.app-name", "My Custom Name")));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("bootlens.client.registration.app-name"))
            .isEqualTo("My Custom Name");
    }

    // --- Helpers ---

    private StandardEnvironment envWithRailway(String serviceName, String environmentName) {
        StandardEnvironment env = new StandardEnvironment();
        addVars(env, Map.of(
            "RAILWAY_SERVICE_NAME",     serviceName,
            "RAILWAY_ENVIRONMENT_NAME", environmentName
        ));
        return env;
    }

    private void addVars(StandardEnvironment env, Map<String, Object> vars) {
        env.getPropertySources().addFirst(new MapPropertySource("test-railway-vars-" + System.nanoTime(), vars));
    }
}
