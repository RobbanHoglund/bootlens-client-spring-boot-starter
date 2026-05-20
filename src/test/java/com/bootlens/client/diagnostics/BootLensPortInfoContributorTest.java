package com.bootlens.client.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.mock.env.MockEnvironment;

class BootLensPortInfoContributorTest {

    @Test
    void contributesDefaultApplicationAndManagementPorts() {
        BootLensPortInfoContributor contributor = new BootLensPortInfoContributor(new MockEnvironment());
        Info.Builder builder = new Info.Builder();

        contributor.contribute(builder);

        Map<String, Object> details = builder.build().getDetails();
        assertThat(details).containsKey("bootlensPorts");
        Map<String, Object> ports = detailMap(details, "bootlensPorts");
        assertThat(detailMap(ports, "application")).containsEntry("port", "8080");
        assertThat(detailMap(ports, "management"))
            .containsEntry("enabled", true)
            .containsEntry("port", "8080")
            .containsEntry("sameAsApplication", true)
            .containsEntry("basePath", "/actuator");
    }

    @Test
    void prefersLocalRuntimePortsWhenAvailable() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("server.port", "0")
            .withProperty("local.server.port", "49152")
            .withProperty("management.server.port", "0")
            .withProperty("local.management.port", "49153")
            .withProperty("management.endpoints.web.base-path", "manage");

        Map<String, Object> ports = BootLensPortInfoContributor.resolvePorts(environment);

        assertThat(detailMap(ports, "application"))
            .containsEntry("port", "49152")
            .containsEntry("localPort", "49152")
            .containsEntry("configuredPort", "0");
        assertThat(detailMap(ports, "management"))
            .containsEntry("enabled", true)
            .containsEntry("port", "49153")
            .containsEntry("localPort", "49153")
            .containsEntry("configuredPort", "0")
            .containsEntry("sameAsApplication", false)
            .containsEntry("basePath", "/manage");
    }

    @Test
    void reportsSeparateConfiguredManagementPort() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("server.port", "9091")
            .withProperty("management.server.port", "9191")
            .withProperty("management.server.address", "127.0.0.1");

        Map<String, Object> ports = BootLensPortInfoContributor.resolvePorts(environment);

        assertThat(detailMap(ports, "application")).containsEntry("port", "9091");
        assertThat(detailMap(ports, "management"))
            .containsEntry("enabled", true)
            .containsEntry("port", "9191")
            .containsEntry("configuredPort", "9191")
            .containsEntry("address", "127.0.0.1")
            .containsEntry("sameAsApplication", false);
    }

    @Test
    void reportsDisabledManagementHttpPort() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("server.port", "9091")
            .withProperty("management.server.port", "-1");

        Map<String, Object> ports = BootLensPortInfoContributor.resolvePorts(environment);

        assertThat(detailMap(ports, "application")).containsEntry("port", "9091");
        assertThat(detailMap(ports, "management"))
            .containsEntry("enabled", false)
            .containsEntry("configuredPort", "-1")
            .doesNotContainKeys("port", "sameAsApplication", "basePath");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Map<String, Object> details, String key) {
        return (Map<String, Object>) details.get(key);
    }
}
