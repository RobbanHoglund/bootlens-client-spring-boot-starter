package com.bootlens.client.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Test
    void concurrentFindDownloadDoesNotThrowConcurrentModificationException() throws Exception {
        // Regression test for P0-4: heapDumps map was a non-synchronized LinkedHashMap.
        // Concurrent reads (findDownload) while entries exist must not throw
        // ConcurrentModificationException or other threading errors.
        //
        // We register a number of heap dumps sequentially first, then read concurrently.
        // This isolates the map-access race condition from the filename-generation check-then-act.
        HeapDumpManager manager = new HeapDumpManager(properties(tempDir));

        // Register several heap dumps sequentially to populate the map
        List<String> registeredIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            HeapDumpManager.HeapDumpTarget target = manager.prepareNewHeapDump();
            Files.writeString(target.path(), "fake heap dump content " + i);
            manager.registerCreatedHeapDump(target);
            registeredIds.add(target.id());
        }

        // Now read concurrently from many threads
        int threadCount = 8;
        int iterations = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < iterations; i++) {
                    for (String id : registeredIds) {
                        // findDownload reads heapDumps map — must not throw
                        manager.findDownload(id);
                    }
                    // isSafeHeapDumpId is also non-trivially safe
                    manager.isSafeHeapDumpId("../../traversal");
                }
            }));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<?> future : futures) {
            assertThatCode(future::get).doesNotThrowAnyException();
        }
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
