# BootLens Client Spring Boot Starter

## Purpose

`bootlens-client-spring-boot-starter` adds BootLens-specific actuator diagnostics to a Spring Boot application.
The first implementation provides a custom `bootlensDiagnostics` actuator endpoint that exposes richer JVM diagnostics backed by the platform `MBeanServer` and the HotSpot `DiagnosticCommand` MBean when available.
It also auto-registers the application with BootLens Server and sends periodic heartbeats by default.

## Current Scope

- Custom BootLens actuator endpoint
- Conservative defaults for sensitive and expensive diagnostics
- Output sanitization and truncation
- Optional heap dump creation and safe download support
- Auto-registration and heartbeat support
- No security yet

Planned later:

- startup diagnostics
- config risk analysis
- deeper thread analysis
- safe runtime operations

## Build And Run

```bash
cd /c/ws/git/bootlens-client-spring-boot-starter
./gradlew test
./gradlew build
```

## How To Add It To A Spring Boot App

Add the starter as a dependency:

```gradle
dependencies {
    implementation project(':bootlens-client-spring-boot-starter')
}
```

or publish/install it and depend on the artifact from your build.

## Diagnostics Properties

Prefix:

`bootlens.client.diagnostics`

Available properties:

- `enabled=true`
- `endpoint-enabled=true`
- `allow-sensitive=false`
- `allow-expensive=true`
- `log-class-histogram-at-vm-exit=false`
- `thread-dump-to-file=false`
- `sanitize-secrets=true`
- `sanitize-privacy=true`
- `include-classpath=false`
- `max-output-chars=2000000`
- `heap-dump.enabled=false`
- `heap-dump.directory=`
- `heap-dump.include-live-only=true`
- `heap-dump.max-files=3`
- `heap-dump.max-age=PT1H`
- `heap-dump.allow-download=false`

Example:

```properties
management.endpoints.web.exposure.include=health,info,metrics,loggers,threaddump,bootlensDiagnostics

bootlens.client.diagnostics.enabled=true
bootlens.client.diagnostics.endpoint-enabled=true
bootlens.client.diagnostics.allow-sensitive=false
bootlens.client.diagnostics.allow-expensive=true
bootlens.client.diagnostics.sanitize-privacy=true
bootlens.client.diagnostics.include-classpath=false
```

Heap dump example for local debugging only:

```properties
bootlens.client.diagnostics.heap-dump.enabled=true
bootlens.client.diagnostics.heap-dump.directory=${java.io.tmpdir}/bootlens-heapdumps
bootlens.client.diagnostics.heap-dump.include-live-only=true
bootlens.client.diagnostics.heap-dump.max-files=3
bootlens.client.diagnostics.heap-dump.max-age=PT1H
bootlens.client.diagnostics.heap-dump.allow-download=true
```

## Registration Properties

Prefix:

`bootlens.client.registration`

Available properties:

- `enabled=true`
- `server-url=http://localhost:9090`
- `app-id=`
- `app-name=`
- `instance-id=`
- `display-name=`
- `base-url=`
- `actuator-base-url=`
- `environment=`
- `region=`
- `team=`
- `zone=`
- `slot=`
- `heartbeat-interval=PT10S`
- `register-on-startup=true`
- `deregister-on-shutdown=true`
- `labels.*=...`

Defaults are resolved conservatively:

- `app-name` falls back to `spring.application.name`
- `app-id` is derived from `app-name` using kebab-case normalization
- `instance-id` falls back to `appId-hostname-port`
- `base-url` falls back to `http://localhost:${server.port}`
- `actuator-base-url` falls back to `http://localhost:${management.server.port or server.port}/actuator`
- `environment` falls back to the first active Spring profile, otherwise `local`

Example:

```properties
bootlens.client.registration.enabled=true
bootlens.client.registration.server-url=http://localhost:9090
bootlens.client.registration.app-id=bootlens-demo
bootlens.client.registration.app-name=BootLens Demo
bootlens.client.registration.instance-id=bootlens-demo-app-local-${server.port}
bootlens.client.registration.base-url=http://localhost:${server.port}
bootlens.client.registration.actuator-base-url=http://localhost:${server.port}/actuator
bootlens.client.registration.environment=local
bootlens.client.registration.region=eu-local
bootlens.client.registration.team=platform
bootlens.client.registration.heartbeat-interval=PT10S
```

When the application becomes ready, the starter:

1. registers with `POST /api/registry/instances`
2. sends heartbeats to `POST /api/registry/instances/{instanceId}/heartbeat`
3. attempts `DELETE /api/registry/instances/{instanceId}` on shutdown when enabled

Registration and heartbeats are best effort. If BootLens Server is unavailable, the application keeps running and the next heartbeat cycle retries automatically.

BootLens Server controls online/offline visibility using its own registry policies, for example heartbeat TTL and offline retention.

## Endpoint Exposure

Expected actuator paths:

- `GET /actuator/bootlensDiagnostics`
- `GET /actuator/bootlensDiagnostics/operations`
- `POST /actuator/bootlensDiagnostics/{operation}`
- `GET /actuator/bootlensDiagnostics/heap-dumps/{id}` when heap dump download is enabled

Supported operations:

- `VM_FLAGS`
- `GC_CLASS_HISTOGRAM`
- `THREAD_DUMP`
- `THREAD_DUMP_VT`
- `HEAP_INFO`
- `HEAP_DUMP`
- `VM_INFORMATION`
- `COMMAND_LINE`
- `METASPACE`
- `SYSTEM_PROPERTIES`
- `VM_EVENTS`
- `CLASSES`
- `VIRTUAL_THREADS_INFO`
- `SECURITY_REPORT`
- `ENV`

## Example curl Commands

```bash
curl http://localhost:9091/actuator/bootlensDiagnostics
curl http://localhost:9091/actuator/bootlensDiagnostics/operations
curl -X POST http://localhost:9091/actuator/bootlensDiagnostics/THREAD_DUMP
curl -X POST http://localhost:9091/actuator/bootlensDiagnostics/ENV
curl -X POST http://localhost:9091/actuator/bootlensDiagnostics/HEAP_DUMP
curl -O http://localhost:9091/actuator/bootlensDiagnostics/heap-dumps/{id}
```

## Safety Notes

- Sensitive diagnostics are blocked by default.
- Expensive diagnostics can be disabled independently.
- Heap dump creation is disabled by default and must be explicitly enabled.
- Heap dumps are highly sensitive and may contain secrets, tokens, private data, cached objects, and full in-memory application state.
- Heap dump creation can pause or slow the JVM and heap dump files can be very large.
- `sanitize-privacy=true` masks local usernames, home directories, temp paths, machine names, local working directories, and other privacy-sensitive runtime values.
- `include-classpath=false` omits `java.class.path` from `SYSTEM_PROPERTIES` output by default because classpaths are often very large and reveal local machine paths.
- Output is sanitized by default for both common secret-like keys and privacy-sensitive runtime values.
- Output is truncated when it exceeds `max-output-chars`.
- For local debugging, you can explicitly enable classpath output with `bootlens.client.diagnostics.include-classpath=true`.
- Heap dump download is only available for heap dumps created by BootLens and only when `bootlens.client.diagnostics.heap-dump.allow-download=true`.
- The starter does not change standard Spring Boot Actuator behavior for existing endpoints.
- Registration and heartbeat calls are best effort and do not fail application startup if BootLens Server is unavailable.
