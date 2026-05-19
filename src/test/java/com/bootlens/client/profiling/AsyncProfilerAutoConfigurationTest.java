package com.bootlens.client.profiling;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class AsyncProfilerAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(
            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                AsyncProfilerAutoConfiguration.class
            )
        );

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
        .withConfiguration(
            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                AsyncProfilerAutoConfiguration.class
            )
        );

    @Test
    void beansAreRegisteredByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AsyncProfilerService.class);
            assertThat(context).hasSingleBean(AsyncProfilerEndpoint.class);
            assertThat(context).hasSingleBean(AsyncProfilerProperties.class);
        });
    }

    @Test
    void servletApplicationsUseActuatorEndpointWithoutRegisteringFallbackController() {
        webContextRunner.run(context -> {
            assertThat(context).hasSingleBean(AsyncProfilerService.class);
            assertThat(context).hasSingleBean(AsyncProfilerEndpoint.class);
            assertThat(context).doesNotHaveBean("asyncProfilerController");
        });
    }

    @Test
    void beansAreNotRegisteredWhenDisabled() {
        contextRunner
            .withPropertyValues("bootlens.client.profiler.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(AsyncProfilerService.class);
                assertThat(context).doesNotHaveBean(AsyncProfilerEndpoint.class);
            });
    }

    @Test
    void customOutputDirIsApplied() {
        contextRunner
            .withPropertyValues("bootlens.client.profiler.output-dir=/custom/dir")
            .run(context -> {
                AsyncProfilerProperties props = context.getBean(AsyncProfilerProperties.class);
                assertThat(props.getOutputDir()).isEqualTo("/custom/dir");
            });
    }

    @Test
    void customDefaultEventIsApplied() {
        contextRunner
            .withPropertyValues("bootlens.client.profiler.default-event=alloc")
            .run(context -> {
                AsyncProfilerProperties props = context.getBean(AsyncProfilerProperties.class);
                assertThat(props.getDefaultEvent()).isEqualTo("alloc");
            });
    }

    @Test
    void customDefaultFormatIsApplied() {
        contextRunner
            .withPropertyValues("bootlens.client.profiler.default-format=jfr")
            .run(context -> {
                AsyncProfilerProperties props = context.getBean(AsyncProfilerProperties.class);
                assertThat(props.getDefaultFormat())
                    .isEqualTo(AsyncProfilerProperties.OutputFormat.JFR);
            });
    }

    @Test
    void maxDurationDefaultIsFiveMinutes() {
        contextRunner.run(context -> {
            AsyncProfilerProperties props = context.getBean(AsyncProfilerProperties.class);
            assertThat(props.getMaxDuration().toSeconds()).isEqualTo(300);
        });
    }

    @Test
    void jstackdepthDefaultIsZero() {
        contextRunner.run(context -> {
            AsyncProfilerProperties props = context.getBean(AsyncProfilerProperties.class);
            assertThat(props.getJstackdepth()).isEqualTo(0);
        });
    }

    @Test
    void customJstackdepthIsApplied() {
        contextRunner
            .withPropertyValues("bootlens.client.profiler.jstackdepth=64")
            .run(context -> {
                AsyncProfilerProperties props = context.getBean(AsyncProfilerProperties.class);
                assertThat(props.getJstackdepth()).isEqualTo(64);
            });
    }

    @Test
    void threadsDefaultIsFalse() {
        contextRunner.run(context -> {
            AsyncProfilerProperties props = context.getBean(AsyncProfilerProperties.class);
            assertThat(props.isThreads()).isFalse();
        });
    }

    @Test
    void threadsCanBeEnabled() {
        contextRunner
            .withPropertyValues("bootlens.client.profiler.threads=true")
            .run(context -> {
                AsyncProfilerProperties props = context.getBean(AsyncProfilerProperties.class);
                assertThat(props.isThreads()).isTrue();
            });
    }
}
