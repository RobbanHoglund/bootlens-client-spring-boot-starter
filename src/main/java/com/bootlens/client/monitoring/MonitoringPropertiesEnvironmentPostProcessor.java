package com.bootlens.client.monitoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Keeps the pre-1.0 monitor property names working while the documented
 * namespace moves under {@code bootlens.client.monitoring.*}.
 */
public class MonitoringPropertiesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "bootlens-monitoring-legacy-aliases";

    private static final List<AliasGroup> ALIASES = List.of(
        thresholdAlias("memory.pressure", "bootlens.client.monitoring.memory-pressure"),
        thresholdAlias("file.descriptors", "bootlens.client.monitoring.file-descriptors"),
        thresholdAlias("direct.memory", "bootlens.client.monitoring.direct-memory"),
        thresholdAlias("metaspace", "bootlens.client.monitoring.metaspace"),
        new AliasGroup("gc.pressure", "bootlens.client.monitoring.gc-pressure",
            "enabled",
            "warning-pause-percent",
            "critical-pause-percent",
            "emergency-pause-percent",
            "check-interval"),
        new AliasGroup("thread.deadlock", "bootlens.client.monitoring.thread-deadlock",
            "enabled",
            "check-interval"),
        new AliasGroup("log.errors", "bootlens.client.monitoring.log-errors",
            "enabled",
            "warning-errors-per-interval",
            "critical-errors-per-interval",
            "emergency-errors-per-interval",
            "check-interval")
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> aliases = new LinkedHashMap<>();
        for (AliasGroup alias : ALIASES) {
            addAliases(environment, aliases, alias);
        }

        if (!aliases.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, aliases));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static void addAliases(ConfigurableEnvironment environment,
                                   Map<String, Object> aliases,
                                   AliasGroup alias) {
        for (String property : alias.properties()) {
            String legacyKey = alias.legacyPrefix() + "." + property;
            String newKey = alias.newPrefix() + "." + property;
            if (!environment.containsProperty(newKey) && environment.containsProperty(legacyKey)) {
                aliases.put(newKey, environment.getProperty(legacyKey));
            }
        }
    }

    private static AliasGroup thresholdAlias(String legacyPrefix, String newPrefix) {
        return new AliasGroup(legacyPrefix, newPrefix,
            "enabled",
            "warning-threshold-percent",
            "critical-threshold-percent",
            "emergency-threshold-percent",
            "check-interval");
    }

    private record AliasGroup(String legacyPrefix, String newPrefix, List<String> properties) {
        AliasGroup(String legacyPrefix, String newPrefix, String... properties) {
            this(legacyPrefix, newPrefix, List.of(properties));
        }
    }
}
