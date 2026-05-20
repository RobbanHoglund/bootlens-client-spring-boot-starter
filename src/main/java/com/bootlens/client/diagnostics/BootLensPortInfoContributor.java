package com.bootlens.client.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;

class BootLensPortInfoContributor implements InfoContributor {

    private static final String DEFAULT_SERVER_PORT = "8080";
    private static final String DEFAULT_ACTUATOR_BASE_PATH = "/actuator";

    private final Environment environment;

    BootLensPortInfoContributor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("bootlensPorts", resolvePorts(environment));
    }

    static Map<String, Object> resolvePorts(Environment environment) {
        String localServerPort = firstNonBlank(environment.getProperty("local.server.port"), null);
        String configuredServerPort = firstNonBlank(environment.getProperty("server.port"), null);
        String serverPort = firstNonBlank(localServerPort, firstNonBlank(configuredServerPort, DEFAULT_SERVER_PORT));

        String localManagementPort = firstNonBlank(environment.getProperty("local.management.port"), null);
        String configuredManagementPort = firstNonBlank(environment.getProperty("management.server.port"), null);
        boolean managementDisabled = "-1".equals(configuredManagementPort);
        String managementPort = managementDisabled
            ? null
            : firstNonBlank(localManagementPort, firstNonBlank(configuredManagementPort, serverPort));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("application", applicationInfo(environment, serverPort, localServerPort, configuredServerPort));
        info.put(
            "management",
            managementInfo(
                environment,
                managementPort,
                localManagementPort,
                configuredManagementPort,
                serverPort,
                managementDisabled
            )
        );
        return Map.copyOf(info);
    }

    private static Map<String, Object> applicationInfo(
        Environment environment,
        String port,
        String localPort,
        String configuredPort
    ) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("port", port);
        putIfPresent(info, "localPort", localPort);
        putIfPresent(info, "configuredPort", configuredPort);
        putIfPresent(info, "address", environment.getProperty("server.address"));
        putIfPresent(info, "sslEnabled", environment.getProperty("server.ssl.enabled"));
        return Map.copyOf(info);
    }

    private static Map<String, Object> managementInfo(
        Environment environment,
        String port,
        String localPort,
        String configuredPort,
        String serverPort,
        boolean disabled
    ) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("enabled", !disabled);
        if (!disabled) {
            info.put("port", port);
            info.put("sameAsApplication", port != null && port.equals(serverPort));
            info.put("basePath", resolveActuatorBasePath(environment));
        }
        putIfPresent(info, "localPort", localPort);
        putIfPresent(info, "configuredPort", configuredPort);
        putIfPresent(info, "address", environment.getProperty("management.server.address"));
        putIfPresent(info, "sslEnabled", environment.getProperty("management.server.ssl.enabled"));
        return Map.copyOf(info);
    }

    private static String resolveActuatorBasePath(Environment environment) {
        String configuredBasePath = firstNonBlank(
            environment.getProperty("management.endpoints.web.base-path"),
            DEFAULT_ACTUATOR_BASE_PATH
        );
        return configuredBasePath.startsWith("/") ? configuredBasePath : "/" + configuredBasePath;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second;
    }
}
