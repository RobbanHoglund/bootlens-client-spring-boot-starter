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
- Early memory pressure monitor with configurable thresholds and rate-limited logging

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

### 4c. Recommended production actuator security model

This is the recommended model for all BootLens-monitored applications in
production, including BootLens Server when it monitors itself.

Use this model consistently:

1. expose `GET /actuator/health` without authentication
2. protect the rest of `/actuator/**`
3. register callback credentials with:
   - `bootlens.client.registration.actuator-username`
   - `bootlens.client.registration.actuator-password`

Why this is the recommended design:

- it keeps health checks simple for platforms and load balancers
- it avoids exposing `/actuator/info`, `/actuator/metrics`, `/actuator/env`,
  and other operational endpoints to everyone
- it gives BootLens one consistent callback model for all monitored apps
- it prevents one app from "working by accident" only because its actuator is
  fully open

Do not rely on `permitAll` for the whole actuator in production just because
BootLens can read it. That is a convenience shortcut, not the recommended
operating model.

Minimal example when the monitored app protects actuator endpoints:

```properties
management.endpoints.web.exposure.include=health,info,metrics,loggers,threaddump,env,configprops,mappings,caches,scheduledtasks,bootlensDiagnostics
management.endpoint.health.show-details=always

bootlens.client.registration.enabled=true
bootlens.client.registration.server-url=http://bootlens-server.railway.internal:9090
bootlens.client.registration.username=registrant
bootlens.client.registration.password=${BOOTLENS_REGISTRANT_PASSWORD}

bootlens.client.registration.app-id=trading-bot
bootlens.client.registration.app-name=Trading Bot
bootlens.client.registration.instance-id=trading-bot-${HOSTNAME}
bootlens.client.registration.base-url=http://${RAILWAY_PRIVATE_DOMAIN}:${server.port}
bootlens.client.registration.actuator-base-url=http://${RAILWAY_PRIVATE_DOMAIN}:${server.port}/actuator
bootlens.client.registration.actuator-username=${APP_ACTUATOR_USERNAME}
bootlens.client.registration.actuator-password=${APP_ACTUATOR_PASSWORD}

bootlens.client.registration.environment=prod
bootlens.client.registration.region=eu-west
bootlens.client.registration.team=platform
```

If BootLens shows an error like:

- `BootLens reached the monitored application, but actuator access was denied (401 UNAUTHORIZED for /actuator/info).`

check these things in order:

1. the monitored app protects `/actuator/info` and related endpoints
2. `bootlens.client.registration.actuator-username` is set correctly
3. `bootlens.client.registration.actuator-password` is set correctly
4. the deployed client library version actually supports sending actuator
   callback credentials in the registration payload
5. BootLens server received and stored those credentials for the registered
   instance

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
- `actuator-username=`
- `actuator-password=`
- `environment=`
- `region=`
- `team=`
- `zone=`
- `slot=`
- `heartbeat-interval=PT10S`
- `register-on-startup=true`
- `deregister-on-shutdown=true`
- `labels.*=...`

Core metadata guidance:

- `environment` is the primary deployment context shown in BootLens filters and metadata cards.
  Use values like `prod`, `staging`, `dev`, or `local`.
- `team` should identify ownership, not infrastructure shape.
  Good examples: `platform`, `payments`, `trading`.
- `region` should identify where the workload runs from an operator point of view.
  Good examples: `eu-west-1`, `us-east-1`, `railway-eu-west`.
- `zone` and `slot` are optional topology details.
  Use them when they genuinely help operators distinguish placements or rollout lanes.
- `labels.*` is the escape hatch for custom dimensions such as `tier`, `topology`, `tenant`, or `cluster`.
  Prefer the first-class properties above for environment, team, and region instead of only putting them in `labels.*`.
- do not duplicate first-class metadata in `labels.*` unless you are migrating older config.
  For example, if you set `environment=prod`, `region=eu-west`, and `team=platform`, you normally should not also set `labels.env`, `labels.region`, or `labels.team`.

What these properties drive in BootLens:

- Fleet overview shows `environment`, `team`, and `region` as first-class metadata.
- Applications and Instances inventory use those same fields in filters and metadata coverage summaries.
- Missing metadata is now shown as `Not reported`, so leaving fields unset is visible to operators.
- Generic tags are still useful, but they should hold secondary dimensions rather than core ownership or placement.

Operational guidance:

- `username` should be the BootLens machine registrant account, not the
  application name
- `server-url` points to the BootLens server, not to the monitored app
- `base-url` should be the deployed application base URL users or operators
  would use
- `actuator-base-url` should be the actual deployed actuator base URL that
  BootLens can reach
- in private service networks, prefer the private hostname and the actual
  in-network protocol that BootLens server uses to reach the app
- if your platform terminates TLS only on the public edge, the internal
  service-to-service URL is often plain `http`, not `https`
- avoid localhost-style fallbacks in hosted environments unless the monitored
  app and its actuator are intentionally only reachable inside the same
  container or pod

Defaults are resolved conservatively:

- `app-name` falls back to `spring.application.name`
- `app-id` is derived from `app-name` using kebab-case normalization
- `instance-id` falls back to `appId-hostname-port`
- `base-url` falls back to `http://localhost:${server.port}`
- `actuator-base-url` falls back to `http://localhost:${management.server.port or server.port}/actuator`
- `actuator-username` and `actuator-password` are optional and should be set only when BootLens server must authenticate against the monitored app's actuator endpoints
- in production, protecting `/actuator/**` except `/actuator/health` and supplying these callback credentials is the recommended model
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
bootlens.client.registration.actuator-username=admin
bootlens.client.registration.actuator-password=${APP_ACTUATOR_PASSWORD}
bootlens.client.registration.environment=local
bootlens.client.registration.region=eu-local
bootlens.client.registration.team=platform
bootlens.client.registration.heartbeat-interval=PT10S
```

Hosted private-network example:

```properties
bootlens.client.registration.enabled=true
bootlens.client.registration.server-url=http://bootlens-server.railway.internal:9090
bootlens.client.registration.username=registrant
bootlens.client.registration.password=${BOOTLENS_REGISTRANT_PASSWORD}

bootlens.client.registration.app-id=trading-bot
bootlens.client.registration.app-name=Trading Bot
bootlens.client.registration.instance-id=trading-bot-${server.port}
bootlens.client.registration.base-url=http://${RAILWAY_PRIVATE_DOMAIN}:${server.port}
bootlens.client.registration.actuator-base-url=http://${RAILWAY_PRIVATE_DOMAIN}:${server.port}/actuator
bootlens.client.registration.actuator-username=admin
bootlens.client.registration.actuator-password=${APP_ACTUATOR_PASSWORD}

bootlens.client.registration.environment=prod
bootlens.client.registration.region=railway-eu-west
bootlens.client.registration.team=trading
bootlens.client.registration.labels.topology=single-instance
```

Field-by-field tips:

- `server-url`
  BootLens server URL, not the monitored app URL.
- `base-url`
  The app URL an operator would conceptually associate with the instance.
  In private-network deployments this is often an internal URL.
- `actuator-base-url`
  The exact actuator base URL that BootLens server can call back into.
  This must match network reality, including whether the internal path is `http` or `https`.
- `actuator-username` / `actuator-password`
  Optional callback credentials BootLens server should use when it reads this
  app's actuator endpoints. Use these when `/actuator/info`, `/actuator/metrics`,
  or related endpoints are protected and BootLens should still monitor the app.
  This is the recommended production design for both regular apps and
  BootLens Server self-monitoring.
- `environment`
  Use short stable values because operators will filter by these often.
- `region`
  Set this explicitly in hosted deployments; do not assume BootLens can infer it.
- `team`
  Set this explicitly if you want Fleet overview and inventory pages to answer “who owns this?” without extra context.
- `labels.topology`, `labels.tier`, `labels.tenant`
  Good examples of extra metadata that belongs in custom labels.

Common mistakes:

- setting `server-url` to the monitored application instead of BootLens server
- using public `https` URLs for internal service-to-service actuator calls when the platform only exposes internal `http`
- omitting `team` and `region` and expecting Fleet overview to infer them
- putting core metadata only in ad hoc custom labels instead of the first-class properties
- duplicating `environment`, `region`, or `team` in both first-class properties and `labels.*`
- leaving `base-url` and `actuator-base-url` on localhost defaults in hosted environments
- protecting `/actuator/**` but forgetting to set `actuator-username` and
  `actuator-password`
- assuming that one app proves callback auth works when that app actually has
  `/actuator/**` configured as `permitAll`

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

## Railway Auto-Configuration

When the application runs on Railway, the starter automatically reads Railway
platform environment variables and uses them as lowest-priority defaults for
`bootlens.client.registration.*` properties. No extra configuration is needed
to get the core registration metadata right on Railway.

### What gets derived automatically

| Property | Derived from |
|---|---|
| `app-name` | `RAILWAY_SERVICE_NAME` |
| `app-id` | kebab-normalised `RAILWAY_SERVICE_NAME` |
| `instance-id` | `{app-id}-{HOSTNAME}` |
| `environment` | `RAILWAY_ENVIRONMENT_NAME` (default: `production`) |
| `region` | `RAILWAY_REGION` (when set) |
| `base-url` | `https://{RAILWAY_PUBLIC_DOMAIN}` (when set) |
| `actuator-base-url` | `http://{RAILWAY_PRIVATE_DOMAIN}:{PORT}/actuator` (preferred) or public domain fallback |
| `labels.railway-service` | `RAILWAY_SERVICE_NAME` |
| `labels.railway-environment` | `RAILWAY_ENVIRONMENT_NAME` |
| `labels.railway-project` | `RAILWAY_PROJECT_NAME` (when set) |

The private domain is preferred for `actuator-base-url` because BootLens
server and the monitored app are typically in the same Railway private network,
making internal `http` both faster and more reliable than the public edge.

### What still requires explicit configuration

- `server-url` — the BootLens server address
- `username` / `password` — BootLens registrant credentials
- `actuator-username` / `actuator-password` — callback credentials if the actuator is protected

### Minimal Railway example

With auto-configuration active, the only required properties are the BootLens
server address and credentials:

```properties
bootlens.client.registration.server-url=http://bootlens-server.railway.internal:9090
bootlens.client.registration.username=registrant
bootlens.client.registration.password=${BOOTLENS_REGISTRANT_PASSWORD}

# Protect actuator and give BootLens server the callback credentials
bootlens.client.registration.actuator-username=${APP_ACTUATOR_USERNAME}
bootlens.client.registration.actuator-password=${APP_ACTUATOR_PASSWORD}
```

Everything else — service name, instance ID, environment, URLs — is picked up
from the Railway environment automatically. You can override any individual
property by setting it explicitly; explicit values always take precedence.

### Behaviour when not on Railway

When `RAILWAY_SERVICE_NAME` is not present in the environment (local development,
other platforms), the post-processor does nothing and the standard defaults apply.

## Memory Pressure Properties

Prefix:

`memory.pressure`

Available properties:

- `enabled=true`
- `warning-threshold-percent=75`
- `critical-threshold-percent=85`
- `emergency-threshold-percent=92`
- `check-interval=60s`

The monitor starts automatically with the application and logs a confirmation line at startup:

```
INFO  Memory pressure monitor active: interval=PT1M, thresholds warning=75% critical=85% emergency=92%
```

When a threshold is exceeded the monitor logs at `WARN` (warning) or `ERROR` (critical and emergency):

```
WARN  Memory pressure WARNING: heap 768/1024 MB (75.0%), container 800/1024 MB (78.1%)
ERROR Memory pressure CRITICAL: heap 880/1024 MB (85.9%), container 900/1024 MB (87.9%)
ERROR Memory pressure EMERGENCY: heap 950/1024 MB (92.8%), container 960/1024 MB (93.8%)
```

Repeated messages at the same level are rate-limited to avoid log spam:

- WARNING: at most once every 10 minutes
- CRITICAL: at most once every 5 minutes
- EMERGENCY: at most once every 2 minutes

A level change always logs immediately. The rate-limit resets when memory pressure drops back below the warning threshold.

Container memory is read from cgroup v2 files (`/sys/fs/cgroup/memory.current` and `memory.max`).
When those files are not available — for example on non-Linux hosts or outside a container — the monitor falls back to JVM heap metrics only and logs a single debug message.
Container percent drives level classification when available; heap metrics are always included in the log output regardless.

The latest snapshot is also exposed at `/actuator/info` under the `memoryPressure` key:

```json
{
  "memoryPressure": {
    "heapUsedMb": 768,
    "heapMaxMb": 1024,
    "heapPercent": 75.0,
    "containerUsedMb": 800,
    "containerMaxMb": 1024,
    "containerPercent": 78.1,
    "checkedAt": "2026-05-15T12:00:00Z"
  }
}
```

The monitor does not affect the health endpoint status.

Minimal example:

```properties
memory.pressure.enabled=true
memory.pressure.warning-threshold-percent=75
memory.pressure.critical-threshold-percent=85
memory.pressure.emergency-threshold-percent=92
memory.pressure.check-interval=60s
```

To disable:

```properties
memory.pressure.enabled=false
```

## File Descriptor Properties

Prefix:

`file.descriptors`

Available properties:

- `enabled=true`
- `warning-threshold-percent=70`
- `critical-threshold-percent=85`
- `emergency-threshold-percent=95`
- `check-interval=60s`

File descriptor exhaustion is a silent failure mode. When all file descriptors
are in use, the JVM can no longer open sockets, files, or pipes. The application
appears to hang or throws `Too many open files` errors that are difficult to
diagnose after the fact. This monitor provides an early warning before the limit
is reached.

**Platform note:** file descriptor monitoring requires `com.sun.management.UnixOperatingSystemMXBean`,
which is available on Linux and macOS but not on Windows. On unsupported platforms
the monitor starts silently, logs a single debug message, and marks the snapshot
as unavailable. No further checks or log entries are produced.

On startup the monitor logs its configuration (Linux/macOS only):

```
INFO  File descriptor monitor active: interval=PT1M, thresholds warning=70% critical=85% emergency=95%, limit=65536
```

When a threshold is exceeded:

```
WARN  File descriptor pressure WARNING: 45875/65536 open (70.0%)
ERROR File descriptor pressure CRITICAL: 55706/65536 open (85.0%)
ERROR File descriptor pressure EMERGENCY: 62259/65536 open (95.0%)
```

Rate-limiting follows the same rules as the other monitors (WARNING 10 min,
CRITICAL 5 min, EMERGENCY 2 min). Level changes always log immediately.

The `/sys/fs/cgroup/memory.*` approach used for container memory has no
equivalent for file descriptors — the JVM limit from the kernel is the
right boundary to track.

The latest snapshot is available at `/actuator/info` under the `fileDescriptors` key:

```json
{
  "fileDescriptors": {
    "available": true,
    "open": 45875,
    "max": 65536,
    "percent": 70.0,
    "checkedAt": "2026-05-15T12:00:00Z"
  }
}
```

On Windows or other unsupported platforms:

```json
{
  "fileDescriptors": {
    "available": false,
    "checkedAt": "2026-05-15T12:00:00Z"
  }
}
```

To disable:

```properties
file.descriptors.enabled=false
```

## GC Pressure Properties

Prefix:

`gc.pressure`

Available properties:

- `enabled=true`
- `warning-pause-percent=5`
- `critical-pause-percent=20`
- `emergency-pause-percent=40`
- `check-interval=60s`

The GC pressure monitor complements the memory pressure monitor. Where the memory
monitor detects *current* heap fill, the GC monitor detects *activity*: how much
wall-clock time the JVM spent in garbage collection during the last interval.
High GC activity is often a leading indicator of an impending OOM problem — the
heap may still look tolerable while the JVM is burning 20% of its time collecting.

The monitor runs on a configurable fixed schedule and calculates GC pause time
as a percentage of the actual interval length, making the thresholds
interval-independent. On startup it logs its configuration:

```
INFO  GC pressure monitor active: interval=PT1M, thresholds warning=5% critical=20% emergency=40%
```

When a threshold is exceeded:

```
WARN  GC pressure WARNING: 3000ms pause in 60000ms interval (5.0%), cumulative 120 collections / 8500ms
ERROR GC pressure CRITICAL: 12000ms pause in 60000ms interval (20.0%), cumulative 210 collections / 22000ms
```

Rate-limiting follows the same rules as the memory pressure monitor (WARNING 10 min,
CRITICAL 5 min, EMERGENCY 2 min). Level changes always log immediately.

The first check after startup establishes a baseline and does not produce a log
entry, since there is no previous reference point to compute a delta from.

The latest snapshot is available at `/actuator/info` under the `gcPressure` key:

```json
{
  "gcPressure": {
    "intervalCollections": 15,
    "intervalPauseMs": 3000,
    "intervalPausePercent": 5.0,
    "totalCollections": 120,
    "totalPauseMs": 8500,
    "checkedAt": "2026-05-15T12:00:00Z"
  }
}
```

To disable:

```properties
gc.pressure.enabled=false
```

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
