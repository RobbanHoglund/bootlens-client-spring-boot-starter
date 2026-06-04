package com.bootlens.client.registration;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Reads Railway platform environment variables and supplies them as lowest-priority
 * default values for {@code bootlens.client.registration.*} properties.
 *
 * <p>Activates only when {@code RAILWAY_SERVICE_NAME} is present in the environment,
 * which is always the case on Railway. Any property the operator sets explicitly in
 * {@code application.properties} or via environment overrides takes precedence.</p>
 *
 * <p>Derived defaults (all overridable):</p>
 * <ul>
 *   <li>{@code app-name} ← {@code RAILWAY_SERVICE_NAME}</li>
 *   <li>{@code app-id} ← kebab-normalised {@code RAILWAY_SERVICE_NAME}</li>
 *   <li>{@code instance-id} ← {@code app-id}-{@code HOSTNAME}</li>
 *   <li>{@code environment} ← {@code RAILWAY_ENVIRONMENT_NAME} (default: production)</li>
 *   <li>{@code region} ← {@code RAILWAY_REGION} (when set)</li>
 *   <li>{@code base-url} ← {@code https://RAILWAY_PUBLIC_DOMAIN} (when set)</li>
 *   <li>{@code actuator-base-url} ← {@code http://RAILWAY_PRIVATE_DOMAIN:{server.port or PORT}/actuator}
 *       (preferred) or public domain fallback</li>
 *   <li>{@code labels.railway-service} ← {@code RAILWAY_SERVICE_NAME}</li>
 *   <li>{@code labels.railway-environment} ← {@code RAILWAY_ENVIRONMENT_NAME}</li>
 *   <li>{@code labels.railway-project} ← {@code RAILWAY_PROJECT_NAME} (when set)</li>
 * </ul>
 *
 * <p>The following still require explicit configuration:</p>
 * <ul>
 *   <li>{@code server-url} — the BootLens server address</li>
 *   <li>{@code username} / {@code password} — BootLens registrant credentials</li>
 *   <li>{@code actuator-username} / {@code actuator-password} — callback credentials
 *       when the actuator is protected</li>
 * </ul>
 */
public class RailwayRegistrationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String SOURCE_NAME = "bootlens-railway-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String serviceName = environment.getProperty("RAILWAY_SERVICE_NAME");
        if (serviceName == null || serviceName.isBlank()) {
            return;
        }

        String environmentName = environment.getProperty("RAILWAY_ENVIRONMENT_NAME", "production");
        String hostname        = environment.getProperty("HOSTNAME", "unknown");
        String port            = firstNonBlank(environment.getProperty("server.port"), environment.getProperty("PORT", "8080"));
        String privateDomain   = environment.getProperty("RAILWAY_PRIVATE_DOMAIN");
        String publicDomain    = environment.getProperty("RAILWAY_PUBLIC_DOMAIN");
        String region          = environment.getProperty("RAILWAY_REGION");
        String projectName     = environment.getProperty("RAILWAY_PROJECT_NAME");

        String appId      = toKebabCase(serviceName);
        String instanceId = appId + "-" + hostname;

        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("bootlens.client.registration.app-name",   serviceName);
        defaults.put("bootlens.client.registration.app-id",     appId);
        defaults.put("bootlens.client.registration.instance-id", instanceId);
        defaults.put("bootlens.client.registration.environment", environmentName);
        defaults.put("bootlens.client.registration.labels.railway-service",     serviceName);
        defaults.put("bootlens.client.registration.labels.railway-environment", environmentName);

        if (hasValue(publicDomain)) {
            defaults.put("bootlens.client.registration.base-url", "https://" + publicDomain);
        }
        if (hasValue(privateDomain)) {
            defaults.put("bootlens.client.registration.actuator-base-url",
                "http://" + privateDomain + ":" + port + "/actuator");
        } else if (hasValue(publicDomain)) {
            defaults.put("bootlens.client.registration.actuator-base-url",
                "https://" + publicDomain + "/actuator");
        }
        if (hasValue(region)) {
            defaults.put("bootlens.client.registration.region", region);
        }
        if (hasValue(projectName)) {
            defaults.put("bootlens.client.registration.labels.railway-project", projectName);
        }

        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    static String toKebabCase(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        return input.trim()
                    .toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
    }

    private static boolean hasValue(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        return hasValue(first) ? first : second;
    }
}
