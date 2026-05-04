package com.bootlens.client.registration;

import java.util.Map;

public record BootLensHeartbeatRequest(
    String status,
    Map<String, String> metadata
) {
}
