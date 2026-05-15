package com.bootlens.client.monitoring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "log.errors")
public class LogErrorRateProperties {

    private boolean enabled = true;
    private int warningErrorsPerInterval  = 10;
    private int criticalErrorsPerInterval = 50;
    private int emergencyErrorsPerInterval = 200;
    private Duration checkInterval = Duration.ofSeconds(60);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getWarningErrorsPerInterval() { return warningErrorsPerInterval; }
    public void setWarningErrorsPerInterval(int warningErrorsPerInterval) { this.warningErrorsPerInterval = warningErrorsPerInterval; }

    public int getCriticalErrorsPerInterval() { return criticalErrorsPerInterval; }
    public void setCriticalErrorsPerInterval(int criticalErrorsPerInterval) { this.criticalErrorsPerInterval = criticalErrorsPerInterval; }

    public int getEmergencyErrorsPerInterval() { return emergencyErrorsPerInterval; }
    public void setEmergencyErrorsPerInterval(int emergencyErrorsPerInterval) { this.emergencyErrorsPerInterval = emergencyErrorsPerInterval; }

    public Duration getCheckInterval() { return checkInterval; }
    public void setCheckInterval(Duration checkInterval) { this.checkInterval = checkInterval; }
}
