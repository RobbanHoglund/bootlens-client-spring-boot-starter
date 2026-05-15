package com.bootlens.client.monitoring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "file.descriptors")
public class FileDescriptorProperties {

    private boolean enabled = true;
    private int warningThresholdPercent = 70;
    private int criticalThresholdPercent = 85;
    private int emergencyThresholdPercent = 95;
    private Duration checkInterval = Duration.ofSeconds(60);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWarningThresholdPercent() {
        return warningThresholdPercent;
    }

    public void setWarningThresholdPercent(int warningThresholdPercent) {
        this.warningThresholdPercent = warningThresholdPercent;
    }

    public int getCriticalThresholdPercent() {
        return criticalThresholdPercent;
    }

    public void setCriticalThresholdPercent(int criticalThresholdPercent) {
        this.criticalThresholdPercent = criticalThresholdPercent;
    }

    public int getEmergencyThresholdPercent() {
        return emergencyThresholdPercent;
    }

    public void setEmergencyThresholdPercent(int emergencyThresholdPercent) {
        this.emergencyThresholdPercent = emergencyThresholdPercent;
    }

    public Duration getCheckInterval() {
        return checkInterval;
    }

    public void setCheckInterval(Duration checkInterval) {
        this.checkInterval = checkInterval;
    }
}
