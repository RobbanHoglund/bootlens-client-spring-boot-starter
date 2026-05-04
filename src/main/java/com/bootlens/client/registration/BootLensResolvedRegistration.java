package com.bootlens.client.registration;

import java.util.Map;

record BootLensResolvedRegistration(
    String serverUrl,
    String instanceId,
    String appId,
    String appName,
    String displayName,
    String baseUrl,
    String actuatorBaseUrl,
    String environment,
    String region,
    String team,
    String zone,
    String slot,
    Map<String, String> labels,
    Map<String, String> metadata
) {

    BootLensRegistrationRequest toRegistrationRequest() {
        return new BootLensRegistrationRequest(
            instanceId,
            appId,
            appName,
            displayName,
            baseUrl,
            actuatorBaseUrl,
            environment,
            region,
            team,
            zone,
            slot,
            labels,
            metadata
        );
    }

    BootLensHeartbeatRequest toHeartbeatRequest() {
        return new BootLensHeartbeatRequest("UP", metadata);
    }
}
