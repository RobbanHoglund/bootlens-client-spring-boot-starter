package com.bootlens.client.registration;

import java.util.Map;

public record BootLensRegistrationRequest(
    String instanceId,
    String appId,
    String appName,
    String displayName,
    String baseUrl,
    String actuatorBaseUrl,
    String actuatorUsername,
    String actuatorPassword,
    String environment,
    String region,
    String team,
    String zone,
    String slot,
    Map<String, String> labels,
    Map<String, String> metadata
) {
}
