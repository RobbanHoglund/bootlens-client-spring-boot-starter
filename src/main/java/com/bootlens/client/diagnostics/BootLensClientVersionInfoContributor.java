package com.bootlens.client.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

class BootLensClientVersionInfoContributor implements InfoContributor {

    private static final String PROPERTIES_PATH = "META-INF/bootlens-client.properties";

    private final Map<String, Object> info;

    BootLensClientVersionInfoContributor() {
        this(BootLensClientVersionInfoContributor.class.getClassLoader(), PROPERTIES_PATH);
    }

    BootLensClientVersionInfoContributor(ClassLoader classLoader, String resourcePath) {
        this.info = load(classLoader, resourcePath);
    }

    @Override
    public void contribute(Info.Builder builder) {
        if (!info.isEmpty()) {
            builder.withDetail("bootlensClient", info);
        }
    }

    static Map<String, Object> load(ClassLoader classLoader, String path) {
        try (InputStream in = classLoader.getResourceAsStream(path)) {
            if (in == null) {
                return Map.of();
            }
            Properties props = new Properties();
            props.load(in);
            return parseProperties(props);
        }
        catch (IOException e) {
            return Map.of();
        }
    }

    static Map<String, Object> parseProperties(Properties props) {
        Map<String, Object> map = new LinkedHashMap<>();
        addIfResolved(map, "version", props.getProperty("bootlens.client.version"));
        addIfResolved(map, "artifact", props.getProperty("bootlens.client.artifact"));
        return Collections.unmodifiableMap(map);
    }

    private static void addIfResolved(Map<String, Object> map, String key, String value) {
        if (value != null && !value.startsWith("${")) {
            map.put(key, value);
        }
    }
}
