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
- HTTP Basic registration support for secured BootLens servers

Planned later:

- startup diagnostics
- config risk analysis
- deeper thread analysis
- safe runtime operations

## License

This repository is published under the Apache License 2.0. See
[LICENSE](./LICENSE).

## Requirements

- Java 25
- Spring Boot 4.0.6 baseline
- A build that can authenticate to GitHub Packages when consuming the published artifact

This starter is currently built and tested against the Spring Boot 4.0.6 BOM
and uses a Java 25 toolchain in its own build.

## Build And Run Locally

```bash
cd /c/ws/git/bootlens-client-spring-boot-starter
./gradlew test
./gradlew build
```

## Versioning And Publishing To GitHub Packages

The starter can now be published as a versioned Maven package to GitHub
Packages.

### Coordinates

- Group: `com.bootlens`
- Artifact: `bootlens-client-spring-boot-starter`
- Version: controlled by `-PbootlensVersion=...` or `BOOTLENS_VERSION`

### Local publish command

From the repo root:

```powershell
$env:GITHUB_ACTOR = '<your-github-username>'
$env:GITHUB_TOKEN = '<your-github-packages-token>'
$env:BOOTLENS_VERSION = '0.1.0'
.\gradlew.bat publish `
  -PgithubPackagesOwner=<github-owner> `
  -PgithubPackagesRepository=bootlens-client-spring-boot-starter
```

Notes:

- `GITHUB_TOKEN` must be able to write packages
- the package is published to:
  `https://maven.pkg.github.com/<github-owner>/<github-repository>`
- if the repo is being built inside GitHub Actions, owner and repository can be
  inferred automatically from the workflow environment

### Publish from GitHub Actions

This repo now includes:

- [.github/workflows/release-client-starter.yml](./.github/workflows/release-client-starter.yml)
- [.github/workflows/publish-github-packages.yml](./.github/workflows/publish-github-packages.yml)

Supported publish paths:

1. manual release workflow that creates and pushes a tag
2. pushing a tag such as `v0.1.0` or `v0.1.0-rc1`

Recommended release flow:

1. merge the intended code to `main`
2. run `Release BootLens client starter`
3. enter a version such as `0.1.0` or `0.1.0-rc1`
4. the workflow runs tests
5. the workflow publishes the package to GitHub Packages
6. the workflow creates and pushes tag `v<version>`
7. the workflow creates a GitHub Release for the same tag

Why the manual release workflow publishes directly:

- tags pushed by a GitHub Actions workflow using the default `GITHUB_TOKEN`
  do not reliably trigger a second workflow for this kind of chained release
  flow
- therefore the release workflow itself performs the package publish
- the tag-based publish workflow still exists as a fallback path for tags pushed
  outside the workflow, for example from a developer machine

The manual release workflow:

1. checks out the repo
2. sets up Java 25
3. runs `./gradlew test`
4. publishes with `./gradlew publish -PbootlensVersion=<resolved-version>`
5. creates and pushes `v<version>`
6. creates a GitHub Release with generated notes if one does not already exist

Important:

- a normal push to `main` does **not** publish a package
- the package is published only from version tags
- the manual release workflow exists so you do not have to create the tag locally

### Publish to Maven local for quick verification

```powershell
$env:BOOTLENS_VERSION = '0.1.0-rc1'
.\gradlew.bat publishToMavenLocal
```

## Quick Start For Consumers

If you just want to get a client connected quickly, follow the five steps in
this section first. The later sections are a fuller reference for properties,
operations, and safety behavior.

### 1. Add the GitHub Packages repository

```gradle
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/<github-owner>/bootlens-client-spring-boot-starter")
        credentials {
            username = findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        }
    }
    mavenCentral()
}
```

GitHub Packages consumption may still require credentials even when this source
repository is public. For friction-free anonymous Java dependency consumption,
Maven Central would be the better long-term distribution target.

### 2. Add the dependency

```gradle
dependencies {
    implementation "com.bootlens:bootlens-client-spring-boot-starter:0.1.0"
}
```

Use the package version, for example `0.1.0` or `0.1.0-rc2`. Do not use the
Git tag form with the `v` prefix.

If you want the build to avoid hardcoded secrets, prefer:

- `GITHUB_ACTOR`
- `GITHUB_TOKEN`

or Gradle properties:

- `gpr.user`
- `gpr.key`

### 3. Expose the required actuator endpoints

```properties
management.endpoints.web.exposure.include=health,info,metrics,loggers,threaddump,bootlensDiagnostics
```

### 4. Configure BootLens registration

Important before you copy the example:

- `username` is the BootLens **registrant account**, not your application name
  and not a human viewer/operator/admin login
- `server-url` must be reachable from the monitored application
- `base-url` and `actuator-base-url` should be real externally or internally
  reachable URLs for the monitored application in its deployed environment

```properties
bootlens.client.registration.enabled=true
bootlens.client.registration.server-url=https://your-bootlens-server.example
bootlens.client.registration.username=registrant
bootlens.client.registration.password=${BOOTLENS_REGISTRANT_PASSWORD}
bootlens.client.registration.app-id=bootlens-demo
bootlens.client.registration.app-name=BootLens Demo
bootlens.client.registration.instance-id=bootlens-demo-${server.port}
bootlens.client.registration.base-url=https://your-app.example
bootlens.client.registration.actuator-base-url=https://your-app.example/actuator
bootlens.client.registration.environment=prod
```

For the full registration property reference, defaults, and behavior notes, see
[Registration Properties](#registration-properties) below.

### 4a. Example: app and BootLens inside the same private Railway network

```properties
bootlens.client.registration.enabled=true
bootlens.client.registration.server-url=http://bootlens-server.railway.internal:8080
bootlens.client.registration.username=registrant
bootlens.client.registration.password=${BOOTLENS_REGISTRANT_PASSWORD}
bootlens.client.registration.app-id=trading-bot
bootlens.client.registration.app-name=Trading Bot
bootlens.client.registration.instance-id=trading-bot-${HOSTNAME}
bootlens.client.registration.base-url=https://trading-bot-production.up.railway.app
bootlens.client.registration.actuator-base-url=https://trading-bot-production.up.railway.app/actuator
bootlens.client.registration.environment=prod
```

Use an internal `server-url` only when the monitored application can actually
resolve and reach that internal BootLens hostname.

### 4b. Example: app outside the BootLens private network

```properties
bootlens.client.registration.enabled=true
bootlens.client.registration.server-url=https://bootlens.example.com
bootlens.client.registration.username=registrant
bootlens.client.registration.password=${BOOTLENS_REGISTRANT_PASSWORD}
bootlens.client.registration.app-id=trading-bot
bootlens.client.registration.app-name=Trading Bot
bootlens.client.registration.instance-id=trading-bot-${HOSTNAME}
bootlens.client.registration.base-url=https://trading-bot.example.com
bootlens.client.registration.actuator-base-url=https://trading-bot.example.com/actuator
bootlens.client.registration.environment=prod
```

Use the public BootLens URL when the monitored application is not inside the
same private network as the BootLens server.

### 5. Start the application and verify registration

When the application becomes ready, the starter:

1. registers with `POST /api/registry/instances`
2. sends heartbeats to `POST /api/registry/instances/{instanceId}/heartbeat`
3. attempts `DELETE /api/registry/instances/{instanceId}` on shutdown when enabled

Registration and heartbeats are best effort. If BootLens Server is unavailable,
the application keeps running and the next heartbeat cycle retries
automatically.

For diagnostic property details and safety defaults, see
[Diagnostics Properties](#diagnostics-properties) and [Safety Notes](#safety-notes).

## Local Development Usage

If you are working on BootLens locally across sibling repos, start here:

- [BootLens server quick start](../bootlens-server/README.md#quick-start-run-bootlens-server-and-connect-your-first-client)

For a composite local setup, you can use the starter as a project dependency:

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

Minimal example:

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
- `username=`
- `password=`
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

Operational guidance:

- `username` should be the BootLens machine registrant account, not the
  application name
- `server-url` points to the BootLens server, not to the monitored app
- `base-url` should be the deployed application base URL users or operators
  would use
- `actuator-base-url` should be the actual deployed actuator base URL that
  BootLens can reach
- avoid localhost-style fallbacks in hosted environments unless the monitored
  app and its actuator are intentionally only reachable inside the same
  container or pod

Defaults are resolved conservatively:

- `app-name` falls back to `spring.application.name`
- `app-id` is derived from `app-name` using kebab-case normalization
- `instance-id` falls back to `appId-hostname-port`
- `base-url` falls back to `http://localhost:${server.port}`
- `actuator-base-url` falls back to `http://localhost:${management.server.port or server.port}/actuator`
- `environment` falls back to the first active Spring profile, otherwise `local`

Reference example:

```properties
bootlens.client.registration.enabled=true
bootlens.client.registration.server-url=http://localhost:9090
bootlens.client.registration.username=registrant
bootlens.client.registration.password=${BOOTLENS_REGISTRANT_PASSWORD}
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

Runtime behavior:

- the client registers on application readiness when `register-on-startup=true`
- it sends periodic heartbeats using `heartbeat-interval`
- it attempts deregistration on shutdown when `deregister-on-shutdown=true`
- registration and heartbeat calls are best effort; BootLens server outages do not fail application startup

BootLens Server controls online/offline visibility using its own registry policies, for example heartbeat TTL and offline retention.

When BootLens server security is enabled, use the dedicated `registrant`
credentials only for registration, heartbeat, and deregistration calls. Those
credentials are intentionally scoped away from operator/admin-only APIs such as
cache eviction, logger mutation, diagnostics execution, and heap-dump download.

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
- Heap dump creation and download should be treated as operator/admin actions in BootLens, not casual browsing features.
- `sanitize-privacy=true` masks local usernames, home directories, temp paths, machine names, local working directories, and other privacy-sensitive runtime values.
- `include-classpath=false` omits `java.class.path` from `SYSTEM_PROPERTIES` output by default because classpaths are often very large and reveal local machine paths.
- Output is sanitized by default for both common secret-like keys and privacy-sensitive runtime values.
- Output is truncated when it exceeds `max-output-chars`.
- For local debugging, you can explicitly enable classpath output with `bootlens.client.diagnostics.include-classpath=true`.
- Heap dump download is only available for heap dumps created by BootLens and only when `bootlens.client.diagnostics.heap-dump.allow-download=true`.
- The starter does not change standard Spring Boot Actuator behavior for existing endpoints.
- Registration and heartbeat calls are best effort and do not fail application startup if BootLens Server is unavailable.
