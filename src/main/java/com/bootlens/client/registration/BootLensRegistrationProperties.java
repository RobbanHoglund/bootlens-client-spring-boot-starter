package com.bootlens.client.registration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Client-side registration settings for connecting an application instance to a
 * BootLens server.
 *
 * <p>The RC security slice extends this configuration with optional credentials
 * so registration, heartbeat, and deregistration calls can authenticate
 * against a protected BootLens API.</p>
 */
@ConfigurationProperties(prefix = "bootlens.client.registration")
public class BootLensRegistrationProperties {

    private boolean enabled = true;
    private String serverUrl = "http://localhost:9090";
    private String username;
    private String password;
    private String appId;
    private String appName;
    private String instanceId;
    private String displayName;
    private String baseUrl;
    private String actuatorBaseUrl;
    private String actuatorUsername;
    private String actuatorPassword;
    private String environment;
    private String region;
    private String team;
    private String zone;
    private String slot;
    private Duration heartbeatInterval = Duration.ofSeconds(10);
    private boolean registerOnStartup = true;
    private boolean deregisterOnShutdown = true;
    private Map<String, String> labels = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    /**
     * Username used when a secured BootLens server requires authenticated
     * registration and heartbeat calls.
     *
     * @return the HTTP Basic username to use for BootLens registry calls
     */
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Password used when a secured BootLens server requires authenticated
     * registration and heartbeat calls.
     *
     * @return the HTTP Basic password to use for BootLens registry calls
     */
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Public or operator-facing base URL associated with this monitored
     * application instance.
     *
     * <p>This should describe the application itself, not the BootLens server.
     * In hosted private-network deployments this is often an internal URL when
     * that is the canonical route BootLens operators reason about.</p>
     *
     * @return the base URL BootLens should associate with the instance
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Actuator base URL that BootLens server can use for callback reads.
     *
     * <p>This value must match real network reachability from BootLens server to
     * the monitored application, including whether the internal path uses HTTP
     * or HTTPS.</p>
     *
     * @return the concrete actuator base URL reachable by BootLens server
     */
    public String getActuatorBaseUrl() {
        return actuatorBaseUrl;
    }

    public void setActuatorBaseUrl(String actuatorBaseUrl) {
        this.actuatorBaseUrl = actuatorBaseUrl;
    }

    /**
     * Optional username BootLens server should use when it calls this
     * instance's actuator endpoints.
     *
     * @return actuator HTTP Basic username BootLens server can use
     */
    public String getActuatorUsername() {
        return actuatorUsername;
    }

    public void setActuatorUsername(String actuatorUsername) {
        this.actuatorUsername = actuatorUsername;
    }

    /**
     * Optional password BootLens server should use when it calls this
     * instance's actuator endpoints.
     *
     * @return actuator HTTP Basic password BootLens server can use
     */
    public String getActuatorPassword() {
        return actuatorPassword;
    }

    public void setActuatorPassword(String actuatorPassword) {
        this.actuatorPassword = actuatorPassword;
    }

    /**
     * Primary deployment environment shown in BootLens filters and metadata.
     *
     * @return environment identifier such as prod, staging, dev, or local
     */
    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    /**
     * Primary runtime region shown in BootLens fleet metadata.
     *
     * @return operator-facing region identifier such as eu-west-1
     */
    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * Owning team shown in BootLens fleet metadata.
     *
     * @return logical owner name such as platform, payments, or trading
     */
    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getSlot() {
        return slot;
    }

    public void setSlot(String slot) {
        this.slot = slot;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public boolean isRegisterOnStartup() {
        return registerOnStartup;
    }

    public void setRegisterOnStartup(boolean registerOnStartup) {
        this.registerOnStartup = registerOnStartup;
    }

    public boolean isDeregisterOnShutdown() {
        return deregisterOnShutdown;
    }

    public void setDeregisterOnShutdown(boolean deregisterOnShutdown) {
        this.deregisterOnShutdown = deregisterOnShutdown;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels == null ? new LinkedHashMap<>() : labels;
    }
}
