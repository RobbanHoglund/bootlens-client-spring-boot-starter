package com.bootlens.client.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapDumpManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void createsSafeHeapDumpFileName() throws Exception {
        HeapDumpManager manager = new HeapDumpManager(properties(tempDir));

        HeapDumpManager.HeapDumpTarget target = manager.prepareNewHeapDump();

        assertThat(target.id()).matches("bootlens-heapdump-[A-Za-z0-9-]+");
        assertThat(target.path().getFileName().toString()).matches("bootlens-heapdump-[A-Za-z0-9-]+\\.hprof");
        assertThat(target.path().getParent()).isEqualTo(tempDir);
    }

    @Test
    void cleanupOnlyDeletesMatchingBootLensFiles() throws Exception {
        Files.writeString(tempDir.resolve("bootlens-heapdump-old.hprof"), "old");
        Files.writeString(tempDir.resolve("keep-me.txt"), "keep");
        Files.writeString(tempDir.resolve("heapdump.hprof"), "keep");
        Files.setLastModifiedTime(tempDir.resolve("bootlens-heapdump-old.hprof"), FileTime.from(Instant.now().minus(Duration.ofHours(3))));

        BootLensDiagnosticsProperties properties = properties(tempDir);
        properties.getHeapDump().setMaxAge(Duration.ofMinutes(5));
        HeapDumpManager manager = new HeapDumpManager(properties);

        manager.prepareNewHeapDump();

        assertThat(Files.exists(tempDir.resolve("bootlens-heapdump-old.hprof"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("keep-me.txt"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("heapdump.hprof"))).isTrue();
    }

    @Test
    void downloadRejectsPathTraversalAttempts() {
        HeapDumpManager manager = new HeapDumpManager(properties(tempDir));

        assertThat(manager.findDownload("../../file")).isEmpty();
    }

    private static BootLensDiagnosticsProperties properties(Path directory) {
        BootLensDiagnosticsProperties properties = new BootLensDiagnosticsProperties();
        properties.getHeapDump().setEnabled(true);
        properties.getHeapDump().setAllowDownload(true);
        properties.getHeapDump().setDirectory(directory.toString());
        properties.getHeapDump().setMaxAge(Duration.ofHours(1));
        properties.getHeapDump().setMaxFiles(3);
        return properties;
    }
}
