package com.bootlens.client.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

class BootLensClientVersionInfoContributorTest {

    // --- parseProperties ---

    @Test
    void parsesVersionAndArtifact() {
        Properties props = new Properties();
        props.setProperty("bootlens.client.version", "0.1.0");
        props.setProperty("bootlens.client.artifact", "bootlens-client-spring-boot-starter");

        Map<String, Object> info = BootLensClientVersionInfoContributor.parseProperties(props);

        assertThat(info).containsEntry("version", "0.1.0")
                        .containsEntry("artifact", "bootlens-client-spring-boot-starter");
    }

    @Test
    void unresolvedVersionPlaceholderIsSkipped() {
        Properties props = new Properties();
        props.setProperty("bootlens.client.version", "${version}");
        props.setProperty("bootlens.client.artifact", "bootlens-client-spring-boot-starter");

        Map<String, Object> info = BootLensClientVersionInfoContributor.parseProperties(props);

        assertThat(info).doesNotContainKey("version")
                        .containsEntry("artifact", "bootlens-client-spring-boot-starter");
    }

    @Test
    void unresolvedArtifactPlaceholderIsSkipped() {
        Properties props = new Properties();
        props.setProperty("bootlens.client.version", "0.1.0");
        props.setProperty("bootlens.client.artifact", "${artifact}");

        Map<String, Object> info = BootLensClientVersionInfoContributor.parseProperties(props);

        assertThat(info).containsEntry("version", "0.1.0")
                        .doesNotContainKey("artifact");
    }

    @Test
    void emptyPropertiesProducesEmptyMap() {
        Map<String, Object> info = BootLensClientVersionInfoContributor.parseProperties(new Properties());
        assertThat(info).isEmpty();
    }

    // --- load ---

    @Test
    void loadsFromTestPropertiesFile() {
        Map<String, Object> info = BootLensClientVersionInfoContributor.load(
            getClass().getClassLoader(),
            "bootlens-client-test.properties"
        );

        assertThat(info).containsEntry("version", "1.2.3-TEST")
                        .containsEntry("artifact", "bootlens-client-spring-boot-starter");
    }

    @Test
    void returnsEmptyMapWhenResourcePathDoesNotExist() {
        Map<String, Object> info = BootLensClientVersionInfoContributor.load(
            getClass().getClassLoader(),
            "META-INF/nonexistent.properties"
        );

        assertThat(info).isEmpty();
    }

    @Test
    void packagedStarterPropertiesAreResolvedOnTheClasspath() {
        Map<String, Object> info = BootLensClientVersionInfoContributor.load(
            getClass().getClassLoader(),
            "META-INF/bootlens-client.properties"
        );

        assertThat(info).containsKeys("version", "artifact");
        assertThat(info.get("version").toString()).doesNotStartWith("${");
        assertThat(info.get("artifact").toString()).doesNotStartWith("${");
    }

    // --- contribute ---

    @Test
    void contributesBootlensClientDetailWhenVersionPresent() {
        BootLensClientVersionInfoContributor contributor = new BootLensClientVersionInfoContributor(
            getClass().getClassLoader(),
            "bootlens-client-test.properties"
        );
        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);

        assertThat(builder.build().getDetails()).containsKey("bootlensClient");
    }

    @Test
    void contributesNothingWhenResourceFileAbsent() {
        BootLensClientVersionInfoContributor contributor = new BootLensClientVersionInfoContributor(
            getClass().getClassLoader(),
            "META-INF/nonexistent.properties"
        );
        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);

        assertThat(builder.build().getDetails()).doesNotContainKey("bootlensClient");
    }
}
