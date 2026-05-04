package com.bootlens.client.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class HeapDumpManager {

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpManager.class);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
        .withZone(ZoneOffset.UTC);
    private static final Pattern HEAP_DUMP_FILE_PATTERN = Pattern.compile("^bootlens-heapdump-[a-zA-Z0-9-]+\\.hprof$");
    private static final Pattern HEAP_DUMP_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9-]+$");

    private final BootLensDiagnosticsProperties properties;
    private final Map<String, StoredHeapDump> heapDumps = new LinkedHashMap<>();

    HeapDumpManager(BootLensDiagnosticsProperties properties) {
        this.properties = properties;
    }

    Optional<String> availabilityIssue() {
        if (!properties.getHeapDump().isEnabled()) {
            return Optional.of("Heap dump is disabled by client configuration.");
        }

        try {
            Path directory = resolveHeapDumpDirectory();
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory)) {
                return Optional.of("Heap dump directory is not a directory.");
            }
            if (!Files.isWritable(directory)) {
                return Optional.of("Heap dump directory is not writable.");
            }
            return Optional.empty();
        } catch (IOException exception) {
            logger.warn("BootLens heap dump directory is unavailable: {}", exception.getMessage());
            return Optional.of("Heap dump directory is unavailable: " + exception.getMessage());
        }
    }

    HeapDumpTarget prepareNewHeapDump() throws IOException {
        Path directory = resolveHeapDumpDirectory();
        Files.createDirectories(directory);
        cleanup(directory, Instant.now());

        long pid = ProcessHandle.current().pid();
        Instant createdAt = Instant.now();
        String id = "bootlens-heapdump-" + FILE_TIMESTAMP.format(createdAt) + "-" + pid;
        Path path = directory.resolve(id + ".hprof");
        int collisionCounter = 1;
        while (Files.exists(path)) {
            id = "bootlens-heapdump-" + FILE_TIMESTAMP.format(createdAt) + "-" + pid + "-" + collisionCounter++;
            path = directory.resolve(id + ".hprof");
        }
        return new HeapDumpTarget(id, path, createdAt);
    }

    BootLensHeapDumpResult registerCreatedHeapDump(HeapDumpTarget target) throws IOException {
        long sizeBytes = Files.size(target.path());
        BootLensHeapDumpResult result = new BootLensHeapDumpResult(
            target.id(),
            target.path().getFileName().toString(),
            sizeBytes,
            target.createdAt(),
            properties.getHeapDump().isIncludeLiveOnly(),
            properties.getHeapDump().isAllowDownload()
        );
        heapDumps.put(target.id(), new StoredHeapDump(target.path(), result));
        trimExcessFiles(target.path().getParent());
        logger.info("BootLens created heap dump {}", result.fileName());
        return result;
    }

    void deleteIncompleteHeapDump(HeapDumpTarget target) {
        if (target == null) {
            return;
        }

        try {
            Files.deleteIfExists(target.path());
        } catch (IOException exception) {
            logger.warn("BootLens failed to delete incomplete heap dump {}: {}", target.path().getFileName(), exception.getMessage());
        }
    }

    Optional<StoredHeapDump> findDownload(String id) {
        if (!properties.getHeapDump().isAllowDownload() || !isSafeHeapDumpId(id)) {
            return Optional.empty();
        }

        StoredHeapDump storedHeapDump = heapDumps.get(id);
        if (storedHeapDump == null) {
            return Optional.empty();
        }

        if (!Files.exists(storedHeapDump.path())) {
            heapDumps.remove(id);
            return Optional.empty();
        }

        return Optional.of(storedHeapDump);
    }

    String renderSuccessMessage(BootLensHeapDumpResult result) {
        return String.join(
            System.lineSeparator(),
            "Heap dump created successfully.",
            "File name: " + result.fileName(),
            "Size: " + humanSize(result.sizeBytes()),
            "Live objects only: " + result.liveOnly(),
            "Download available: " + result.downloadAvailable()
        );
    }

    boolean isSafeHeapDumpId(String id) {
        return id != null && HEAP_DUMP_ID_PATTERN.matcher(id).matches();
    }

    private void cleanup(Path directory, Instant now) throws IOException {
        Duration maxAge = properties.getHeapDump().getMaxAge();
        Instant cutoff = maxAge == null ? null : now.minus(maxAge);
        List<Path> candidates = listManagedHeapDumpFiles(directory);

        for (Path candidate : candidates) {
            if (cutoff != null && Files.getLastModifiedTime(candidate).toInstant().isBefore(cutoff)) {
                deleteManagedFile(candidate);
            }
        }

        trimExcessFiles(directory);
    }

    private void trimExcessFiles(Path directory) throws IOException {
        int maxFiles = Math.max(1, properties.getHeapDump().getMaxFiles());
        List<Path> files = listManagedHeapDumpFiles(directory);
        if (files.size() <= maxFiles) {
            return;
        }

        for (int index = maxFiles; index < files.size(); index++) {
            deleteManagedFile(files.get(index));
        }
    }

    private List<Path> listManagedHeapDumpFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }

        List<PathWithTime> files = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> HEAP_DUMP_FILE_PATTERN.matcher(path.getFileName().toString()).matches())
                .forEach(path -> files.add(new PathWithTime(path, lastModified(path))));
        }

        return files.stream()
            .sorted(Comparator.comparing(PathWithTime::modifiedAt).reversed())
            .map(PathWithTime::path)
            .toList();
    }

    private FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return FileTime.fromMillis(0L);
        }
    }

    private void deleteManagedFile(Path path) throws IOException {
        Files.deleteIfExists(path);
        heapDumps.entrySet().removeIf(entry -> entry.getValue().path().equals(path));
        logger.info("BootLens deleted heap dump {}", path.getFileName());
    }

    private Path resolveHeapDumpDirectory() {
        String configuredDirectory = properties.getHeapDump().getDirectory();
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir"), "bootlens-heapdumps");
        }
        return Path.of(configuredDirectory);
    }

    private String humanSize(long sizeBytes) {
        if (sizeBytes >= 1024L * 1024L) {
            return (sizeBytes / (1024L * 1024L)) + " MB";
        }
        if (sizeBytes >= 1024L) {
            return (sizeBytes / 1024L) + " KB";
        }
        return sizeBytes + " bytes";
    }

    record HeapDumpTarget(String id, Path path, Instant createdAt) {
    }

    record StoredHeapDump(Path path, BootLensHeapDumpResult result) {
    }

    private record PathWithTime(Path path, FileTime modifiedAt) {
    }
}
