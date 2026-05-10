package com.bootlens.client.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.bootlens.client.diagnostics.BootLensDiagnosticsAutoConfiguration;
import com.bootlens.client.diagnostics.BootLensDiagnosticsEndpoint;
import com.bootlens.client.diagnostics.BootLensDiagnosticsWebExtension;
import com.bootlens.client.diagnostics.SecretSanitizer;
import com.bootlens.client.diagnostics.VmDiagnostics;
import com.bootlens.client.registration.BootLensRegistrationAutoConfiguration;
import com.bootlens.client.registration.BootLensRegistrationClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class BootLensAutoConfigurationIntegrationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                BootLensDiagnosticsAutoConfiguration.class,
                BootLensRegistrationAutoConfiguration.class
            )
        );

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                BootLensDiagnosticsAutoConfiguration.class,
                BootLensRegistrationAutoConfiguration.class
            )
        );

    @Test
    void diagnosticsBeansAreRegisteredByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("secretSanitizer");
            assertThat(context).hasBean("heapDumpManager");
            assertThat(context).hasBean("vmDiagnostics");
            assertThat(context).hasSingleBean(BootLensDiagnosticsEndpoint.class);
        });
    }

    @Test
    void diagnosticsBeansAreNotRegisteredWhenDiagnosticsAreDisabled() {
        contextRunner
            .withPropertyValues("bootlens.client.diagnostics.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean("secretSanitizer");
                assertThat(context).doesNotHaveBean("heapDumpManager");
                assertThat(context).doesNotHaveBean("vmDiagnostics");
                assertThat(context).doesNotHaveBean(BootLensDiagnosticsEndpoint.class);
            });
    }

    @Test
    void endpointCanBeDisabledWithoutTurningOffSupportingDiagnosticsBeans() {
        contextRunner
            .withPropertyValues("bootlens.client.diagnostics.endpoint-enabled=false")
            .run(context -> {
                assertThat(context).hasBean("secretSanitizer");
                assertThat(context).hasBean("heapDumpManager");
                assertThat(context).hasBean("vmDiagnostics");
                assertThat(context).doesNotHaveBean(BootLensDiagnosticsEndpoint.class);
            });
    }

    @Test
    void servletWebExtensionIsRegisteredForWebApplications() {
        webContextRunner.run(context -> assertThat(context).hasSingleBean(BootLensDiagnosticsWebExtension.class));
    }

    @Test
    void registrationBeansAreRegisteredByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("bootLensRegistrationTransport");
            assertThat(context).hasBean("bootLensRegistrationClock");
            assertThat(context).hasSingleBean(BootLensRegistrationClient.class);
            assertThat(context).hasBean("bootLensRegistrationLifecycle");
        });
    }

    @Test
    void registrationBeansAreNotRegisteredWhenRegistrationIsDisabled() {
        contextRunner
            .withPropertyValues("bootlens.client.registration.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean("bootLensRegistrationTransport");
                assertThat(context).doesNotHaveBean("bootLensRegistrationClock");
                assertThat(context).doesNotHaveBean(BootLensRegistrationClient.class);
                assertThat(context).doesNotHaveBean("bootLensRegistrationLifecycle");
            });
    }
}
