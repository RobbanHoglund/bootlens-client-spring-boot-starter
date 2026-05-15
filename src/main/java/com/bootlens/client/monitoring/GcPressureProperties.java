package com.bootlens.client.monitoring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gc.pressure")
public class GcPressureProperties {

    private boolean enabled = true;
    private int warningPausePercent = 5;
    private int criticalPausePercent = 20;
    private int emergencyPausePercent = 40;
    private Duration checkInterval = Duration.ofSeconds(60);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWarningPausePercent() {
        return warningPausePercent;
    }

    public void setWarningPausePercent(int warningPausePercent) {
        this.warningPausePercent = warningPausePercent;
    }

    public int getCriticalPausePercent() {
        return criticalPausePercent;
    }

    public void setCriticalPausePercent(int criticalPausePercent) {
        this.criticalPausePercent = criticalPausePercent;
    }

    public int getEmergencyPausePercent() {
        return emergencyPausePercent;
    }

    public void setEmergencyPausePercent(int emergencyPausePercent) {
        this.emergencyPausePercent = emergencyPausePercent;
    }

    public Duration getCheckInterval() {
        return checkInterval;
    }

    public void setCheckInterval(Duration checkInterval) {
        this.checkInterval = checkInterval;
    }
}
