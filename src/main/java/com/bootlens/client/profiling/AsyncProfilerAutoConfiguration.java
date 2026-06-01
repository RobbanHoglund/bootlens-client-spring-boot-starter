package com.bootlens.client.profiling;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the embedded async-profiler integration.
 *
 * <p>Activates when:
 * <ul>
 *   <li>the {@code one.profiler.AsyncProfiler} class is on the classpath
 *       (provided by {@code me.bechberger:ap-loader-all}), and</li>
 *   <li>{@code bootlens.client.profiler.enabled} is {@code true} (default).</li>
 * </ul>
 *
 * <p>The native profiler runtime is loaded when the service bean is created so
 * availability problems are visible at startup. Profiling sessions remain
 * explicit and on-demand. If the platform is unsupported (e.g. Windows) the
 * endpoint starts up normally and all operations return a {@code UNAVAILABLE}
 * status rather than failing application startup.
 */
@AutoConfiguration
@ConditionalOnClass(name = "one.profiler.AsyncProfiler")
@ConditionalOnProperty(
    prefix = "bootlens.client.profiler",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
@EnableConfigurationProperties(AsyncProfilerProperties.class)
public class AsyncProfilerAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public AsyncProfilerService asyncProfilerService(AsyncProfilerProperties properties) {
        return new AsyncProfilerService(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AsyncProfilerEndpoint asyncProfilerEndpoint(AsyncProfilerService service) {
        return new AsyncProfilerEndpoint(service);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public AsyncProfilerWebExtension asyncProfilerWebExtension(AsyncProfilerService service) {
        return new AsyncProfilerWebExtension(service);
    }
}
