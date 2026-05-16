package com.bootlens.client.monitoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

class BootLensMonitorHealthIndicator implements HealthIndicator {

    private final List<MonitorLevelSource> sources;

    BootLensMonitorHealthIndicator(List<MonitorLevelSource> sources) {
        this.sources = sources;
    }

    @Override
    public Health health() {
        MemoryLevel worst = MemoryLevel.OK;
        Map<String, Object> details = new LinkedHashMap<>();

        for (MonitorLevelSource source : sources) {
            MemoryLevel level = source.currentLevel();
            details.put(source.name(), level.name());
            if (level.ordinal() > worst.ordinal()) {
                worst = level;
            }
        }

        details.put("worstLevel", worst.name());

        Health.Builder builder = switch (worst) {
            case EMERGENCY -> Health.down();
            case CRITICAL  -> Health.outOfService();
            default        -> Health.up();
        };

        return builder.withDetails(details).build();
    }
}
