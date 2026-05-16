package com.bootlens.client.monitoring;

/**
 * Implemented by each monitor's auto-configuration to expose the monitor's
 * current pressure level. The health indicator collects all registered
 * MonitorLevelSource beans and reports the worst level.
 */
interface MonitorLevelSource {
    String name();
    MemoryLevel currentLevel();
}
