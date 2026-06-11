# Changelog

All notable changes to `bootlens-client-spring-boot-starter` are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).  
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Security
- `BootLensRegistrationClient` now logs a one-time `WARN` when HTTP Basic registry
  credentials are configured but the server URL uses plaintext `http://`, so credentials
  travelling unencrypted are surfaced instead of silently sent.
- Extended `SecretSanitizer.SENSITIVE_TERMS` with `url`, `dsn`, and `connection` to mask
  common connection-string environment variables (`DATABASE_URL`, `REDIS_URL`, `JDBC_URL`, etc.)
  that carry embedded credentials.

### Fixed
- Startup registration is now fully exception-safe: `BootLensRegistrationLifecycle`
  wraps `attemptRegistration` in a `try/catch` so an unexpected `RuntimeException` on the
  `ApplicationReadyEvent` path can never propagate out of `SpringApplication.run()` and
  break the host application's boot.
- `AsyncProfilerService.shutdown()` is now `synchronized` and idempotent, so repeated or
  concurrent bean-destruction calls no longer race with `start()`/`stop()`.
- `demo.cache.backend` hard-coded demo property removed from `BootLensRegistrationClient`.
  The cache backend label is now read from `bootlens.client.registration.cache-backend`
  (or inferred from `spring.cache.type` as before).
- `HeapDumpManager.heapDumps` map changed from plain `LinkedHashMap` to
  `Collections.synchronizedMap(new LinkedHashMap<>())` to prevent race conditions
  between concurrent HTTP threads.
- `AsyncProfilerEndpoint.download()` moved to a new `AsyncProfilerWebExtension`
  (`@EndpointWebExtension`) so that the web-specific `WebEndpointResponse` type is only
  activated in Servlet web contexts.
- `BootLensRegistrationAutoConfiguration.bootLensRegistrationClock` bean now uses a
  named qualifier (`bootLensRegistrationClock`) instead of the broad
  `@ConditionalOnMissingBean(Clock.class)` to avoid polluting the application context
  with a generic `java.time.Clock` bean.
- Dead code `VmDiagnostics.isSecurityManagerPresent()` removed.
- `BootLensClientInfoAutoConfiguration` now respects `bootlens.client.diagnostics.enabled`
  so that info contributors (`bootlensClient`, `bootlensPorts`) are not registered when
  diagnostics are disabled.

### Changed
- **Breaking (distribution):** the artifact is now published to **Maven Central** instead
  of GitHub Packages, and its group id changed from `com.bootlens` to
  `io.github.robbanhoglund`. Update the dependency to
  `io.github.robbanhoglund:bootlens-client-spring-boot-starter:<version>` and remove any
  GitHub Packages repository block — Maven Central needs no repository or credentials.
  Publishing now uses the `com.vanniktech.maven.publish` plugin and the Sonatype Central
  Portal; the `publish-github-packages.yml` workflow was removed.
- Health-indicator properties moved from `bootlens.client.health.*` to
  `bootlens.client.monitoring.health.*` for consistency with the other monitors. The
  legacy `bootlens.client.health.enabled` key still works as a deprecated alias via
  `MonitoringPropertiesEnvironmentPostProcessor`.
- `me.bechberger:ap-loader-all` dependency scope changed from `api` to `implementation`.
  Consumers no longer receive the async-profiler native library (≈50 MB) on their
  compile classpath transitively. If you reference `one.profiler.*` types directly,
  add `ap-loader-all` to your own build.
- `org.springframework.boot:spring-boot-starter-actuator` dependency scope changed from
  `api` to `implementation`. Consumers who rely on actuator APIs should declare the
  dependency explicitly in their own build.
- Rate limiting added for expensive diagnostic operations: by default, the same expensive
  operation (`GC_CLASS_HISTOGRAM`, `THREAD_DUMP`, `HEAP_DUMP`, etc.) may not be invoked
  more than once every 30 seconds. Returns `RATE_LIMITED` status when the cooldown is
  active. Configure with `bootlens.client.diagnostics.expensive-operation-cooldown`.
- Profiler output files now have a retention policy. The `output-dir` is cleaned before
  each new session: files older than `max-output-age` (default 2 h) or exceeding
  `max-output-files` (default 5) are deleted automatically.

### Added
- `bootlens.client.registration.cache-backend` property — optional explicit cache backend
  label for instance metadata.
- `bootlens.client.profiler.max-output-files` (default `5`) — limits profiler output
  file count; oldest files are removed before each new session.
- `bootlens.client.profiler.max-output-age` (default `PT2H`) — files older than this are
  removed before each new session.
- `bootlens.client.diagnostics.expensive-operation-cooldown` (default `PT30S`) — rate
  limit for expensive diagnostic operations.
- `BootLensDiagnosticStatus.RATE_LIMITED` — new status returned when an expensive
  operation is invoked within the cooldown window.
- `AsyncProfilerWebExtension` — new class that provides the profiler download endpoint
  exclusively in Servlet web contexts.

---

## [0.1.0] — Initial release

### Added
- `bootlensDiagnostics` actuator endpoint exposing JVM diagnostics backed by
  `DiagnosticCommand` MBean with output sanitization and truncation.
- Auto-registration and periodic heartbeat against BootLens Server.
- HTTP Basic authentication for registration and heartbeat calls.
- Railway platform auto-configuration via `RAILWAY_*` environment variables.
- Memory pressure, GC pause, file descriptor, direct memory, thread deadlock,
  log error rate, and metaspace monitors with configurable thresholds.
- Monitor health indicator aggregating all monitor levels into `/actuator/health`.
- Embedded async-profiler integration via `bootlensProfiler` actuator endpoint:
  CPU, wall, ctimer, alloc, lock, nativemem, and nativememleak events;
  flamegraph, JFR, tree, and collapsed output formats; live in-memory snapshots.
- `SecretSanitizer` masking sensitive keys in diagnostic output.
- Heap dump creation and safe download via `bootlensDiagnostics`.
- CI workflow for main branch pushes and pull requests.
- Release workflow publishing to GitHub Packages.
