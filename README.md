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
- Railway auto-configuration: zero-config registration metadata on Railway
- Early memory pressure monitor with configurable thresholds and rate-limited logging
- GC pause time monitor with configurable thresholds and rate-limited logging
- File descriptor monitor with configurable thresholds (Linux/macOS)
- Direct (off-heap) memory monitor — detects NIO/Netty buffer pressure invisible to heap monitors
- Thread deadlock detector — catches silent deadlocks and logs immediately with thread names
- Log error rate monitor — alerts when ERROR log volume spikes above a threshold
- Metaspace monitor — tracks class-loader memory; alerts before metaspace is exhausted
- Monitor health indicator — aggregates all monitor levels into `/actuator/health`
- Embedded async-profiler — CPU, allocation, wall-clock, lock, ctimer, and native memory profiling via `/actuator/bootlensProfiler` with per-event sampling defaults, flame graph and JFR download, and live in-memory snapshots

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
- Spring Boot 4.x
- A build that can authenticate to GitHub Packages when consuming the published artifact

This starter is built and tested against the Spring Boot 4.0.6 BOM with a Java 25 toolchain.
Spring Boot 3.x is not supported by the 1.0.x line.

## Build And Run Locally

```bash
cd /path/to/bootlens-client-spring-boot-starter
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

Registration is enabled by default. When the starter is on the classpath, the
client attempts registration on application readiness and then sends periodic
heartbeats to BootLens Server. These calls are best effort and do not fail
application startup if BootLens Server is unavailable. Set
`bootlens.client.registration.enabled=false` to opt out entirely.

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
bootlens.client.registration.server-url=http://bootlens-server.railway.internal:8080
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

### General properties

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Set to `false` to disable all diagnostics features |
| `endpoint-enabled` | `true` | Set to `false` to disable only the actuator endpoint |
| `allow-sensitive` | `false` | Enables sensitive operations such as `ENV` and `SECURITY_REPORT` |
| `allow-expensive` | `true` | Enables expensive operations such as `GC_CLASS_HISTOGRAM` |
| `log-class-histogram-at-vm-exit` | `false` | Logs a class histogram to the JVM log on process exit |
| `thread-dump-to-file` | `false` | Writes thread dumps to disk in addition to the response |
| `sanitize-secrets` | `true` | Masks common secret-like property keys in output |
| `sanitize-privacy` | `true` | Masks paths, usernames, and machine names in output |
| `include-classpath` | `false` | Includes `java.class.path` in `SYSTEM_PROPERTIES` output |
| `endpoint-timing-header-enabled` | `true` | Adds BootLens endpoint timing response headers for actuator requests |
| `max-output-chars` | `2000000` | Truncates response output at this character count |
| `expensive-operation-cooldown` | `PT30S` | Minimum time between successive executions of the same expensive operation (e.g. `GC_CLASS_HISTOGRAM`, `THREAD_DUMP`, `HEAP_DUMP`). Set to `PT0S` to disable rate limiting. |

### Health diagnostics properties

| Property | Default | Notes |
|---|---|---|
| `health-diagnostics.enabled` | `true` | Enables the on-demand BootLens health contributor latency endpoint |
| `health-diagnostics.timeout` | `PT5S` | Maximum time allowed for one explicit health diagnostics capture |
| `health-diagnostics.max-contributor-count` | `128` | Maximum health contributors included in one diagnostics response |
| `health-diagnostics.max-details-entries` | `8` | Maximum health detail entries summarized per contributor |

### Heap dump properties

| Property | Default | Notes |
|---|---|---|
| `heap-dump.enabled` | `false` | Must be explicitly enabled; treat as an operator action |
| `heap-dump.directory` | *(empty)* | Defaults to the system temp directory when not set |
| `heap-dump.include-live-only` | `true` | Dumps only live (reachable) objects |
| `heap-dump.max-files` | `3` | Older dumps are deleted automatically |
| `heap-dump.max-age` | `PT1H` | Dumps older than this are deleted automatically |
| `heap-dump.allow-download` | `false` | Must be explicitly enabled to allow file download via actuator |

Minimal example:

```properties
management.endpoints.web.exposure.include=health,info,metrics,loggers,threaddump,bootlensDiagnostics

bootlens.client.diagnostics.enabled=true
bootlens.client.diagnostics.endpoint-enabled=true
bootlens.client.diagnostics.allow-sensitive=false
bootlens.client.diagnostics.allow-expensive=true
bootlens.client.diagnostics.sanitize-privacy=true
bootlens.client.diagnostics.include-classpath=false
bootlens.client.diagnostics.endpoint-timing-header-enabled=true
bootlens.client.diagnostics.health-diagnostics.enabled=true
bootlens.client.diagnostics.health-diagnostics.timeout=PT5S
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

Registration is default-on. With no explicit opt-out, the client registers on
application readiness, sends heartbeats every `heartbeat-interval`, and attempts
deregistration during graceful shutdown. Use
`bootlens.client.registration.enabled=false` for applications that should never
contact BootLens Server from this starter.

### Server connection

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Set to `false` to disable registration entirely |
| `server-url` | `http://localhost:9090` | BootLens server address — not the monitored app |
| `username` | *(required)* | BootLens registrant account |
| `password` | *(required)* | BootLens registrant password; use `${ENV_VAR}` |

### Identity

| Property | Default | Notes |
|---|---|---|
| `app-id` | *(derived)* | Kebab-case of `app-name`; falls back from `spring.application.name` |
| `app-name` | *(derived)* | Falls back to `spring.application.name` |
| `instance-id` | *(derived)* | Falls back to `{appId}-{hostname}-{port}` |
| `display-name` | *(empty)* | Optional human-readable label shown in BootLens UI |

### Callback URLs

| Property | Default | Notes |
|---|---|---|
| `base-url` | `http://localhost:${server.port}` | Deployed application URL operators associate with this instance |
| `actuator-base-url` | `http://localhost:${port}/actuator` | Must match the URL BootLens can actually reach |
| `actuator-username` | *(empty)* | Callback credential when actuator endpoints are protected |
| `actuator-password` | *(empty)* | Callback credential when actuator endpoints are protected |

The resolved application and management ports are also exposed at `/actuator/info`
under the `bootlensPorts` key:

```json
{
  "bootlensPorts": {
    "application": {
      "port": "9091",
      "localPort": "9091",
      "configuredPort": "0"
    },
    "management": {
      "enabled": true,
      "port": "9191",
      "localPort": "9191",
      "configuredPort": "0",
      "sameAsApplication": false,
      "basePath": "/actuator"
    }
  }
}
```

`localPort` is present when Spring has published the actual runtime port, which is
especially useful for `server.port=0` or `management.server.port=0`.

### Deployment metadata

| Property | Default | Notes |
|---|---|---|
| `environment` | *(derived)* | Falls back to first active Spring profile, then `local` |
| `region` | *(empty)* | Example: `eu-west-1`, `us-east-1`, `railway-eu-west` |
| `team` | *(empty)* | Ownership label — example: `platform`, `payments`, `trading` |
| `zone` | *(empty)* | Optional topology detail for multi-zone deployments |
| `slot` | *(empty)* | Optional rollout lane detail |
| `labels.*` | *(empty)* | Custom key-value metadata for dimensions not covered above |

### Lifecycle

| Property | Default | Notes |
|---|---|---|
| `heartbeat-interval` | `PT10S` | How often periodic heartbeats are sent to BootLens server |
| `register-on-startup` | `true` | Registers with BootLens when the application becomes ready |
| `deregister-on-shutdown` | `true` | Attempts deregistration on graceful shutdown |
| `cache-backend` | *(derived)* | Optional cache backend label for instance metadata. Overrides the value inferred from `spring.cache.type`. Example: `caffeine`, `redis`, `hazelcast`. |

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
bootlens.client.registration.server-url=http://bootlens-server.railway.internal:8080
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
bootlens.client.registration.server-url=http://bootlens-server.railway.internal:8080
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

Monitoring properties use the `bootlens.client.monitoring.*` namespace. The
legacy prefixes `memory.pressure`, `file.descriptors`, `gc.pressure`,
`direct.memory`, `thread.deadlock`, `log.errors`, and `metaspace` remain
supported as aliases for 1.x compatibility, but new configuration should use
the BootLens namespace.

Prefix:

`bootlens.client.monitoring.memory-pressure`

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Set to `false` to disable the monitor |
| `warning-threshold-percent` | `75` | Logs WARN when heap or container memory exceeds this percent |
| `critical-threshold-percent` | `85` | Logs ERROR at critical level |
| `emergency-threshold-percent` | `92` | Logs ERROR at emergency level |
| `check-interval` | `60s` | How often memory is sampled |

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
bootlens.client.monitoring.memory-pressure.enabled=true
bootlens.client.monitoring.memory-pressure.warning-threshold-percent=75
bootlens.client.monitoring.memory-pressure.critical-threshold-percent=85
bootlens.client.monitoring.memory-pressure.emergency-threshold-percent=92
bootlens.client.monitoring.memory-pressure.check-interval=60s
```

To disable:

```properties
bootlens.client.monitoring.memory-pressure.enabled=false
```

## File Descriptor Properties

Prefix:

`bootlens.client.monitoring.file-descriptors`

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Set to `false` to disable the monitor |
| `warning-threshold-percent` | `70` | Logs WARN when open FDs exceed this percent of the limit |
| `critical-threshold-percent` | `85` | Logs ERROR at critical level |
| `emergency-threshold-percent` | `95` | Logs ERROR at emergency level |
| `check-interval` | `60s` | How often file descriptor usage is sampled |

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
bootlens.client.monitoring.file-descriptors.enabled=false
```

## GC Pressure Properties

Prefix:

`bootlens.client.monitoring.gc-pressure`

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Set to `false` to disable the monitor |
| `warning-pause-percent` | `5` | Logs WARN when GC consumed more than this percent of the interval |
| `critical-pause-percent` | `20` | Logs ERROR at critical level |
| `emergency-pause-percent` | `40` | Logs ERROR at emergency level |
| `check-interval` | `60s` | How often GC delta is calculated |

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
bootlens.client.monitoring.gc-pressure.enabled=false
```

## Direct Memory Properties

Prefix:

`bootlens.client.monitoring.direct-memory`

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Set to `false` to disable the monitor |
| `warning-threshold-percent` | `50` | Logs WARN when direct memory usage exceeds this percent of the JVM limit |
| `critical-threshold-percent` | `75` | Logs ERROR at critical level |
| `emergency-threshold-percent` | `90` | Logs ERROR at emergency level |
| `check-interval` | `60s` | How often direct memory usage is sampled |

Direct memory is off-heap memory allocated by `ByteBuffer.allocateDirect()` and used internally by NIO,
Netty, gRPC, and most async networking libraries. It is completely invisible to heap-based monitors
and GC pressure metrics — the JVM heap can look healthy while direct memory runs to exhaustion.
When the limit is reached, new direct buffer allocations throw `OutOfMemoryError: Direct buffer memory`.

The monitor reads the JVM direct memory limit via `sun.misc.VM.maxDirectMemory()`. When the limit
cannot be resolved (rare), the monitor still reports used and capacity bytes in the `/actuator/info`
snapshot but does not classify pressure levels.

The default JVM limit equals the heap max (`-Xmx`) unless explicitly set with `-XX:MaxDirectMemorySize`.

On startup the monitor logs:

```
INFO  Direct memory monitor active: interval=PT1M, thresholds warning=50% critical=75% emergency=90%, maxDirectMemory=256MB
```

When a threshold is exceeded:

```
WARN  Direct memory pressure WARNING: 128/256 MB used (50.0%), 842 buffers, capacity 128 MB
ERROR Direct memory pressure CRITICAL: 192/256 MB used (75.0%), 1204 buffers, capacity 192 MB
ERROR Direct memory pressure EMERGENCY: 230/256 MB used (90.0%), 1531 buffers, capacity 230 MB
```

Rate-limiting follows the same rules as the other monitors (WARNING 10 min, CRITICAL 5 min, EMERGENCY 2 min).
Level changes always log immediately.

The latest snapshot is available at `/actuator/info` under the `directMemory` key:

```json
{
  "directMemory": {
    "available": true,
    "bufferCount": 842,
    "usedMb": 128,
    "capacityMb": 128,
    "maxMb": 256,
    "percent": 50.0,
    "checkedAt": "2026-05-15T12:00:00Z"
  }
}
```

When the JVM limit cannot be resolved:

```json
{
  "directMemory": {
    "available": false,
    "bufferCount": 842,
    "usedMb": 128,
    "capacityMb": 128,
    "checkedAt": "2026-05-15T12:00:00Z"
  }
}
```

To disable:

```properties
bootlens.client.monitoring.direct-memory.enabled=false
```

## Thread Deadlock Properties

Prefix:

`bootlens.client.monitoring.thread-deadlock`

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Set to `false` to disable the detector |
| `check-interval` | `30s` | How often threads are inspected for deadlocks |

A deadlock is a condition where two or more threads are permanently blocked waiting for each other
to release a lock. It produces no exception, no OOM, no health-check change, and no metrics
degradation — the affected threads simply stop making progress. The only observable symptom is
that request handling or background tasks quietly freeze.

The detector calls `ThreadMXBean.findDeadlockedThreads()` on a fixed schedule. This covers both
Java object monitor deadlocks (`synchronized`) and `java.util.concurrent` lock deadlocks (`ReentrantLock`, etc.).

The check interval defaults to 30 seconds — shorter than the other monitors — because a deadlock
that goes undetected for several minutes can cascade into a full application hang.

On startup the detector logs:

```
INFO  Thread deadlock detector active: interval=PT30S
```

When a deadlock is detected, it logs immediately at ERROR regardless of rate limits, with the
thread names and IDs involved:

```
ERROR DEADLOCK DETECTED: 2 thread(s) deadlocked — worker-1 (id=42), worker-2 (id=43)
```

If the deadlock persists across multiple checks, a reminder is logged at most once every 2 minutes.
When the deadlock resolves (threads terminate or locks are released), the detector logs:

```
INFO  Thread deadlock resolved — no deadlocked threads detected
```

The latest snapshot is available at `/actuator/info` under the `threadDeadlock` key:

```json
{
  "threadDeadlock": {
    "deadlocked": false,
    "threadCount": 0,
    "checkedAt": "2026-05-15T12:00:00Z"
  }
}
```

When a deadlock is active:

```json
{
  "threadDeadlock": {
    "deadlocked": true,
    "threadCount": 2,
    "threadNames": ["worker-1 (id=42)", "worker-2 (id=43)"],
    "checkedAt": "2026-05-15T12:00:00Z"
  }
}
```

To disable:

```properties
bootlens.client.monitoring.thread-deadlock.enabled=false
```

## Log Error Rate Properties

Prefix:

`bootlens.client.monitoring.log-errors`

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Set to `false` to disable the monitor |
| `warning-errors-per-interval` | `10` | Logs WARN when ERROR events in the last interval reach this count |
| `critical-errors-per-interval` | `50` | Logs ERROR at critical level |
| `emergency-errors-per-interval` | `200` | Logs ERROR at emergency level |
| `check-interval` | `60s` | How long each counting interval lasts |

**Platform note:** this monitor requires Logback as the logging framework. It is automatically
disabled when Logback is not on the classpath.

A sudden spike in ERROR log volume is typically the earliest observable signal of a production
problem — it usually precedes degraded latency, failed health checks, and customer reports by
minutes or more. This monitor hooks into the root Logback appender and counts `ERROR` and `WARN`
log events per interval without adding any per-log overhead beyond a single atomic counter increment.

The thresholds are counts-per-interval, not percentages, because there is no fixed log volume ceiling.
Tune them to match the normal ERROR baseline for your application — a batch job that logs 5 errors
per minute at baseline should use higher thresholds than an API server that normally logs zero.

On startup the monitor logs:

```
INFO  Log error rate monitor active: interval=PT1M, thresholds warning=10 critical=50 emergency=200 errors/interval
```

When a threshold is exceeded:

```
WARN  Log error rate WARNING: 12 ERROR and 30 WARN log events in last interval (total 47 errors / 203 warns)
ERROR Log error rate CRITICAL: 63 ERROR and 95 WARN log events in last interval (total 110 errors / 298 warns)
```

Rate-limiting follows the same rules as the other monitors (WARNING 10 min, CRITICAL 5 min, EMERGENCY 2 min).
Level changes always log immediately.

The latest snapshot is available at `/actuator/info` under the `logErrors` key:

```json
{
  "logErrors": {
    "errorsInInterval": 12,
    "warnsInInterval": 30,
    "totalErrors": 47,
    "totalWarns": 203,
    "checkedAt": "2026-05-15T12:00:00Z"
  }
}
```

To disable:

```properties
bootlens.client.monitoring.log-errors.enabled=false
```

## Metaspace Properties

Prefix:

`bootlens.client.monitoring.metaspace`

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Set to `false` to disable the monitor |
| `warning-threshold-percent` | `70` | Logs WARN when metaspace usage exceeds this percent of the max |
| `critical-threshold-percent` | `85` | Logs ERROR at critical level |
| `emergency-threshold-percent` | `95` | Logs ERROR at emergency level |
| `check-interval` | `60s` | How often metaspace usage is sampled |

Metaspace holds JVM class metadata — the internal representation of every loaded class and its
methods. It grows when classes are loaded and shrinks (slowly) when class loaders are GC'd.
Applications that generate classes dynamically — Spring-heavy applications with many proxies,
serialization libraries, expression evaluators, hot-reload development tools, or scripting
engines — can leak metaspace gradually until the JVM throws `OutOfMemoryError: Metaspace`.

Metaspace leaks are slow and hard to diagnose retroactively. By the time an OOM occurs, the
root cause (a class loader that was never collected) has often long since disappeared from
thread dumps. This monitor exposes the fill level early.

**Bounded vs. unbounded metaspace:** the JVM default is unlimited metaspace, meaning it
grows as far as the OS allows (`-XX:MaxMetaspaceSize` is not set). In that mode the monitor
reports used and committed bytes informatively but cannot compute a fill percentage and does
not trigger threshold alerts. Set `-XX:MaxMetaspaceSize` explicitly to activate threshold alerts.

On startup the monitor logs one of two messages:

```
# When -XX:MaxMetaspaceSize is set:
INFO  Metaspace monitor active: interval=PT1M, thresholds warning=70% critical=85% emergency=95%, max=512MB

# When max is unlimited:
INFO  Metaspace monitor active: interval=PT1M, no max configured (thresholds inactive — reporting used/committed only)
```

When a threshold is exceeded (requires `-XX:MaxMetaspaceSize`):

```
WARN  Metaspace pressure WARNING: 358/512 MB used (70.0%), committed 360 MB
ERROR Metaspace pressure CRITICAL: 435/512 MB used (85.0%), committed 437 MB
```

Rate-limiting follows the same rules as the other monitors (WARNING 10 min, CRITICAL 5 min, EMERGENCY 2 min).
Level changes always log immediately.

The latest snapshot is available at `/actuator/info` under the `metaspace` key:

```json
{
  "metaspace": {
    "usedMb": 358,
    "committedMb": 360,
    "maxMb": 512,
    "percent": 70.0,
    "checkedAt": "2026-05-15T12:00:00Z"
  }
}
```

When metaspace max is not configured:

```json
{
  "metaspace": {
    "usedMb": 128,
    "committedMb": 130,
    "checkedAt": "2026-05-15T12:00:00Z"
  }
}
```

To activate threshold alerts, set the JVM flag and tune if needed:

```properties
# JVM flag (not a Spring property):
# -XX:MaxMetaspaceSize=512m

# To adjust thresholds:
bootlens.client.monitoring.metaspace.warning-threshold-percent=70
bootlens.client.monitoring.metaspace.critical-threshold-percent=85
bootlens.client.monitoring.metaspace.emergency-threshold-percent=95
```

To disable:

```properties
bootlens.client.monitoring.metaspace.enabled=false
```

## Async Profiler

Async-profiler is a low-overhead sampling profiler for the JVM that uses OS-level APIs to collect
CPU, allocation, wall-clock, lock contention, and native memory data. Unlike JVMTI-based profilers,
it avoids safepoint bias and can profile native frames alongside Java code. This integration bundles
async-profiler inside the Spring Boot application and exposes it through an Actuator endpoint —
start and stop profiling sessions, download flame graphs, and capture live snapshots, all via HTTP,
without connecting any external tooling to the running process.

The bundled dependency (`me.bechberger:ap-loader-all`) ships native libraries for Linux x64,
Linux arm64, and macOS inside the JAR. No manual installation or agent flag is needed on those
platforms.

BootLens loads the native async-profiler runtime when the profiler service bean is created. This is
intentional: container/runtime problems such as a missing native dependency or a no-exec extraction
directory show up immediately in `/actuator/bootlensProfiler` as `UNAVAILABLE`. Actual profiling is
still explicit and on-demand; inventory polling and status checks do not start profiling sessions or
collect profiling data.

### Platform support

Linux x64, Linux arm64, and macOS are supported. On Windows the endpoint starts but every operation
returns `"state": "UNAVAILABLE"` — the service degrades gracefully and never throws.

### Exposing the endpoint

```properties
management.endpoints.web.exposure.include=bootlensProfiler
```

### Properties

Prefix: `bootlens.client.profiler`

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Set to `false` to disable the integration entirely |
| `output-dir` | `${java.io.tmpdir}/bootlens-profiles` | Directory where output files are written |
| `max-output-files` | `5` | Maximum profiling output files to keep; oldest are deleted automatically before each new session |
| `max-output-age` | `PT2H` | Profiling output files older than this are deleted automatically; set to `PT0S` to disable |
| `default-event` | `cpu` | Profiling event used when none is specified in the request |
| `default-duration` | `30s` | Session length when none is specified in the request |
| `default-format` | `flamegraph` | Output format when none is specified in the request |
| `max-duration` | `300s` | Requests for longer sessions are silently capped at this value |
| `jstackdepth` | `0` | Max stack frames captured per sample; `0` means use async-profiler's default (2048). Reduce to e.g. `64` to lower overhead when deep stacks are not of interest. |
| `threads` | `false` | When `true`, each stack trace ends with a frame identifying the thread. Most useful with `wall` profiling. |
| `dump-flat-max-methods` | `50` | Max methods returned by the `/flat` in-memory operation |
| `dump-traces-max-traces` | `10` | Max traces returned by the `/traces` in-memory operation |

### Events

The `event` parameter selects what the profiler measures. The table below lists all supported events,
their purpose, and the default sampling interval or threshold used when none is specified.

| Event | Default interval | What it measures |
|---|---|---|
| `cpu` | `2ms` | CPU-active threads only. Finds hot code paths during CPU spikes or high-throughput scenarios. **This is the default.** |
| `wall` | `100ms` | All threads at regular wall-clock intervals, regardless of CPU state. Finds blocking, sleeping, and I/O-bound threads. Most useful with `threads=true`. |
| `ctimer` | `100ms` | Similar to `cpu` but uses a per-thread context timer instead of OS perf events. Useful in containers where `perf_events` access is restricted. |
| `alloc` | `1k` (per KB allocated) | Heap allocations. Finds code paths responsible for allocation pressure and GC pauses. |
| `lock` | `5ms` (wait threshold) | Java monitor contention. Records locks where threads waited longer than the threshold. Finds synchronization bottlenecks. |
| `nativemem` | `1k` (per KB allocated) | Native (off-heap) memory allocations via `malloc`/`calloc`. Useful for diagnosing Netty buffer growth or JNI leaks. Records allocations only — paired with `nofree`. |
| `nativememleak` | `1k` (per KB allocated) | Same as `nativemem` but retains only allocations without a matching `free`. The resulting flame graph shows likely native memory leaks. |

The event name is case-insensitive and accepts hyphens or underscores: `nativemem`, `NATIVE_MEM`,
and `native-mem` are all equivalent.

For perf-event profiling (`cache-misses`, `cycles`, `instructions`, `page-faults`, etc.) and
hardware breakpoints (`mem:<func>`), pass the async-profiler event string directly — the service
forwards it as-is and uses the profiler's built-in default interval.

### Sampling intervals

The `interval` request parameter controls sampling granularity. Its meaning depends on the event:

| Event | `interval` meaning | Examples |
|---|---|---|
| `cpu`, `wall`, `ctimer` | Time between samples | `2ms`, `500us`, `10000000` (ns) |
| `alloc`, `nativemem`, `nativememleak` | Allocation size between samples | `512k`, `1m`, `4096` (bytes) |
| `lock` | Minimum contention wait before recording | `5ms`, `10ms` |

Accepted unit suffixes: `ns`, `us`, `ms`, `s`, `k`, `m`, `g`. A plain number is treated as
nanoseconds for time events and bytes for size events.

When omitted, the per-event defaults from the table above are used. Smaller intervals increase
resolution and overhead; larger intervals reduce overhead and coarsen the profile.

### Output formats

| Format | Extension | Opens in |
|---|---|---|
| `flamegraph` | `.html` | Any browser. Width of each frame = share of samples. **Default and recommended starting point.** |
| `jfr` | `.jfr` | JDK Mission Control, IntelliJ IDEA. Use when correlating with JFR events or when deeper tooling is needed. |
| `tree` | `.html` | Any browser. Same data as flamegraph but presented as a top-down call tree. |
| `collapsed` | `.txt` | [speedscope](https://www.speedscope.app/), `flamegraph.pl`. Use when processing data in an external tool. |

### Endpoints

#### GET /actuator/bootlensProfiler — current status

When idle with no previous session:

```json
{
  "state": "IDLE",
  "profilerAvailable": true
}
```

When idle after a completed session:

```json
{
  "state": "IDLE",
  "profilerAvailable": true,
  "lastSessionId": "abc123def456",
  "lastOutputFile": "/tmp/bootlens-profiles/bootlens-abc123def456.html",
  "lastFormat": "flamegraph",
  "lastCompletedAt": "2026-05-17T14:00:30Z",
  "lastSessionSucceeded": true
}
```

When a session is running:

```json
{
  "state": "RUNNING",
  "profilerAvailable": true,
  "activeSessionId": "abc123def456",
  "activeEvent": "cpu",
  "activeFormat": "flamegraph",
  "activeInterval": "2ms",
  "activeStartedAt": "2026-05-17T14:00:00Z",
  "activeRemainingSeconds": 24
}
```

On Windows or when the native library fails to load:

```json
{
  "state": "UNAVAILABLE",
  "profilerAvailable": false,
  "profilerLoadError": "no native library for os.name=Windows 11 ..."
}
```

If the error says async-profiler could not load from an extraction directory, the container is
usually mounting that directory without execute permission. BootLens first uses async-profiler's
standard in-process loader (`AsyncProfiler.getInstance()`), then falls back to ap-loader extraction
only if the standard loader fails. The fallback tries several extraction directories before giving
up: an explicit `ap_loader_extraction_dir`, the application working directory, `/app`,
`${java.io.tmpdir}`, and finally a user-cache directory. Operators can override the first candidate
explicitly, for example:

```bash
JAVA_TOOL_OPTIONS="-Dap_loader_extraction_dir=/tmp/bootlens-ap-loader"
```

If all candidates fail, the runtime is blocking native library loading or the extracted native
library is incompatible with the container libc. The bundled ap-loader version tracks
async-profiler 4.4 and supports both glibc and musl, but a specific JVM/container/native
profiler combination can still fail in native code. If a specific event crashes the JVM, inspect
the generated `hs_err_pid*.log`, try a lower sampling rate, or run that profiling workload on a
glibc-based Java image.

If the root cause mentions `libstdc++.so.6`, install the C++ runtime in the image:

```dockerfile
RUN apk add --no-cache libstdc++
```

For Debian or Ubuntu based images use `apt-get install -y libstdc++6`.

---

#### POST /actuator/bootlensProfiler — start a profiling session

All fields are optional and fall back to their configured defaults.

| Field | Type | Description |
|---|---|---|
| `event` | string | Profiling event (see [Events](#events)). Default: `cpu` |
| `durationSeconds` | number | How long to profile. Default: `30`. Capped at `max-duration`. |
| `format` | string | Output format (see [Output formats](#output-formats)). Default: `flamegraph` |
| `interval` | string | Sampling interval or threshold (see [Sampling intervals](#sampling-intervals)). Default: per-event constant. |
| `inverted` | boolean | Flamegraph-only. When `true`, asks async-profiler for icicle/top-down layout. Leave unset or `false` for classic root-at-bottom flamegraphs. |

Minimal request (all defaults apply):

```json
{}
```

CPU profile for 60 seconds:

```json
{ "event": "cpu", "durationSeconds": 60, "format": "flamegraph" }
```

Allocation profile with custom interval (one sample per 512 KB):

```json
{ "event": "alloc", "durationSeconds": 30, "format": "flamegraph", "interval": "512k" }
```

Wall-clock profile at 50 ms resolution, threads annotated:

```json
{ "event": "wall", "durationSeconds": 60, "format": "flamegraph", "interval": "50ms" }
```

JFR capture for JDK Mission Control:

```json
{ "event": "cpu", "durationSeconds": 120, "format": "jfr" }
```

Response when started:

```json
{
  "status": "STARTED",
  "sessionId": "abc123def456",
  "event": "cpu",
  "format": "flamegraph",
  "durationSeconds": 60,
  "interval": "2ms",
  "outputFile": "/tmp/bootlens-profiles/bootlens-abc123def456.html"
}
```

If a session is already active:

```json
{
  "status": "ALREADY_RUNNING",
  "sessionId": "abc123def456",
  "message": "A profiling session is already active."
}
```

On unsupported platforms:

```json
{
  "status": "UNAVAILABLE",
  "message": "async-profiler is not available: ..."
}
```

---

#### DELETE /actuator/bootlensProfiler — stop the current session early

The output file is written with whatever data was collected so far. The `outputFile` field in the
response contains the filename to use for download.

```json
{
  "status": "STOPPED",
  "sessionId": "abc123def456",
  "outputFile": "/tmp/bootlens-profiles/bootlens-abc123def456.html",
  "format": "flamegraph",
  "automatic": false
}
```

`automatic: true` means the profiler stopped itself because the requested duration expired.

If no session is active:

```json
{ "status": "NOT_RUNNING", "message": "No active profiling session." }
```

---

#### GET /actuator/bootlensProfiler/download/{filename} — download an output file

Returns the output file with an appropriate `Content-Type`:

| Format | Content-Type |
|---|---|
| `flamegraph`, `tree` | `text/html` |
| `jfr` | `application/octet-stream` |
| `collapsed` | `text/plain` |

Only the bare filename is accepted. Path separators (`/`, `\`) and `..` are rejected with `400 Bad Request`.

---

#### In-memory dump operations

These endpoints return profiling data directly without writing a new file. They work both during
a running session (live snapshot) and after a session has stopped (inspect the most recent data).

**GET /actuator/bootlensProfiler/flat[?limit=N]** — top-N hottest methods by sample count

`limit` overrides `dump-flat-max-methods` for this request.

```json
{
  "status": "OK",
  "type": "flat",
  "data": "--- Execution profile ---\n  ns  percent  samples  top\n  500000000  50.00%  250  java/lang/Thread.sleep\n..."
}
```

**GET /actuator/bootlensProfiler/traces[?limit=N]** — top-N call traces

`limit` overrides `dump-traces-max-traces` for this request.

```json
{
  "status": "OK",
  "type": "traces",
  "data": "--- 1000000000 ns (10.0%), 250 samples\n  java/lang/Thread.sleep\n  java/util/concurrent/locks/LockSupport.park\n..."
}
```

**GET /actuator/bootlensProfiler/collapsed** — collapsed stacks compatible with FlameGraph and speedscope

```json
{
  "status": "OK",
  "type": "collapsed",
  "data": "java/lang/Thread.sleep;java/util/concurrent/locks/LockSupport.park 250\n..."
}
```

**GET /actuator/bootlensProfiler/samples** — number of samples collected so far

```json
{ "available": true, "samples": 4821, "errorMessage": null }
```

**GET /actuator/bootlensProfiler/version** — native library version

```json
{ "available": true, "version": "3.0", "errorMessage": null }
```

### Typical workflows

**Investigate a CPU spike**

1. Notice high CPU in metrics.
2. Start a `cpu` session — the default 2 ms interval is a good starting point.
3. When the session ends (or stop it early with `DELETE`), download the flamegraph.
4. For a quick text summary without downloading a file, call `/flat?limit=20`.

```bash
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"cpu","durationSeconds":60}'
```

**Find blocking threads (low CPU, high latency)**

Use `wall` profiling to sample all threads regardless of CPU activity. Enable `threads` in
properties or use the global `bootlens.client.profiler.threads=true` to see per-thread breakdowns.

```bash
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"wall","durationSeconds":60,"interval":"50ms"}'
```

**Investigate allocation pressure or GC pauses**

Start an `alloc` session. The resulting flamegraph shows which call paths allocate the most heap.
Increase the interval to reduce overhead in very allocation-heavy applications.

```bash
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"alloc","durationSeconds":30,"interval":"512k"}'
```

**Find lock contention**

```bash
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"lock","durationSeconds":30,"format":"flamegraph"}'
```

**Native memory growth (Netty, NIO, JNI)**

Use `nativemem` to sample native allocations. The flamegraph shows which code paths allocate
off-heap memory. Use `nativememleak` to retain only un-freed allocations and find leaks.
JFR format is required for native memory events.

```bash
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"nativemem","durationSeconds":60,"format":"jfr"}'
```

**Live snapshot during a running session**

Start a long session and inspect it while it runs — useful during live incidents.

```bash
# Start 2-minute session
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"cpu","durationSeconds":120}'

# While it runs: check progress, peek at hottest methods
curl http://localhost:8080/actuator/bootlensProfiler/samples
curl 'http://localhost:8080/actuator/bootlensProfiler/flat?limit=20'

# Stop early and get the file
curl -X DELETE http://localhost:8080/actuator/bootlensProfiler
```

**Profile in a perf-event-restricted container (ctimer)**

When `perf_events` are blocked by the container runtime (common in Kubernetes), use `ctimer`
instead of `cpu` — it uses a per-thread context timer that does not require kernel perf access.

```bash
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"ctimer","durationSeconds":60}'
```

### curl reference

```bash
# Check current status
curl http://localhost:8080/actuator/bootlensProfiler

# Start with all defaults (30s cpu flamegraph)
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{}'

# CPU profile — 60 s, default 2 ms interval
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"cpu","durationSeconds":60}'

# CPU profile — fine-grained 500 µs interval (higher overhead)
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"cpu","durationSeconds":30,"interval":"500us"}'

# Wall-clock profile with per-thread annotation
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"wall","durationSeconds":60,"interval":"50ms"}'

# Allocation profile
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"alloc","durationSeconds":30,"interval":"512k"}'

# Lock contention
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"lock","durationSeconds":30}'

# Native memory
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"nativemem","durationSeconds":60,"format":"jfr"}'

# JFR capture for JDK Mission Control
curl -X POST http://localhost:8080/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"cpu","durationSeconds":120,"format":"jfr"}'

# In-memory snapshots (work during or after a session)
curl http://localhost:8080/actuator/bootlensProfiler/flat
curl 'http://localhost:8080/actuator/bootlensProfiler/flat?limit=10'
curl http://localhost:8080/actuator/bootlensProfiler/traces
curl http://localhost:8080/actuator/bootlensProfiler/collapsed
curl http://localhost:8080/actuator/bootlensProfiler/samples
curl http://localhost:8080/actuator/bootlensProfiler/version

# Stop early
curl -X DELETE http://localhost:8080/actuator/bootlensProfiler

# Download result (use filename from start or stop response)
curl -O http://localhost:8080/actuator/bootlensProfiler/download/bootlens-abc123def456.html
curl -O http://localhost:8080/actuator/bootlensProfiler/download/bootlens-abc123def456.jfr
```

### Security

The download endpoint only serves files from the configured output directory (`output-dir`).
Filenames containing path separators (`/`, `\`) or `..` are rejected. The output path must not
contain commas — if the configured directory path contains a comma the session will fail to start
with a clear error.

In production, protect the profiler endpoint with the same controls applied to other sensitive
actuator endpoints: restrict the actuator port to an internal network interface, or require
authentication via Spring Security. Profiling output contains full stack traces, method names,
and call paths, which can reveal application internals.

### Troubleshooting

**`UNAVAILABLE` on Linux — native library cannot extract**

The profiler needs to extract and execute a native `.so` from the JAR. If the JVM temp directory
is on a filesystem mounted with `noexec` (common in some container setups), extraction fails with
a link error. Set the `-Dap_loader_extraction_dir` system property to a directory on an `exec`-mounted
filesystem to override the extraction target:

```bash
java -Dap_loader_extraction_dir=/var/lib/myapp/profiler -jar myapp.jar
```

The starter tries the following directories in order:
1. `-Dap_loader_extraction_dir` (if set)
2. `{user.dir}/.bootlens-ap-loader` (application working directory)
3. `/app/.bootlens-ap-loader` (common container app root)
4. `{java.io.tmpdir}/bootlens-ap-loader`
5. `{user.home}/.cache/bootlens-ap-loader`

**`UNAVAILABLE` on Alpine / musl — missing C++ runtime**

If the log shows `libstdc++.so.6: cannot open shared object file`, the C++ runtime is missing.
Install it in the image:

```dockerfile
# Alpine
RUN apk add --no-cache libstdc++

# Debian / Ubuntu
RUN apt-get install -y libstdc++6
```

Alternatively, use a JRE image built on glibc (e.g. Eclipse Temurin on Ubuntu) where the runtime
is already present.

To disable:

```properties
bootlens.client.profiler.enabled=false
```

## Monitor Health Indicator

**Spring Boot version note:** the health indicator requires Spring Boot 4.x
(`org.springframework.boot.health.contributor.HealthIndicator`). Spring Boot 3.x is not
supported by the 1.0.x line.

Prefix:

`bootlens.client.health`

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Set to `false` to disable the health indicator entirely |

The monitor health indicator aggregates the current pressure level from every active monitor
into a single entry in `/actuator/health`. This is the only feature in the starter that can
actively influence platform behaviour — load balancers, Railway restart policies, uptime checks,
and Kubernetes liveness or readiness probes all act on health status.

The mapping from monitor level to health status is fixed:

| Monitor level | Health status | When |
|---|---|---|
| `OK` | `UP` | All monitors below warning threshold |
| `WARNING` | `UP` | Elevated but not yet critical — app is still serving traffic normally |
| `CRITICAL` | `OUT_OF_SERVICE` | App is struggling — traffic should be drained if possible |
| `EMERGENCY` | `DOWN` | App should be taken out of rotation immediately |

The indicator reports `DOWN` for thread deadlocks regardless of thresholds, because a deadlock
is always unrecoverable without a restart.

`WARNING` deliberately does not change health status. Warning is an early signal that gives
operators time to react — it should not trigger a restart or traffic shift on its own.

### What shows in `/actuator/health`

Spring Boot aggregates all `HealthIndicator` beans. The BootLens indicator appears under the
`bootLensMonitor` key. The overall application health becomes the worst status across all
indicators.

```json
{
  "status": "UP",
  "components": {
    "bootLensMonitor": {
      "status": "UP",
      "details": {
        "memoryPressure": "OK",
        "gcPressure": "OK",
        "fileDescriptors": "OK",
        "directMemory": "OK",
        "threadDeadlock": "OK",
        "logErrors": "WARNING",
        "metaspace": "OK",
        "worstLevel": "WARNING"
      }
    }
  }
}
```

When a monitor reaches CRITICAL:

```json
{
  "status": "OUT_OF_SERVICE",
  "components": {
    "bootLensMonitor": {
      "status": "OUT_OF_SERVICE",
      "details": {
        "memoryPressure": "CRITICAL",
        "gcPressure": "OK",
        "fileDescriptors": "OK",
        "directMemory": "OK",
        "threadDeadlock": "OK",
        "logErrors": "OK",
        "metaspace": "OK",
        "worstLevel": "CRITICAL"
      }
    }
  }
}
```

### Platform behaviour at each status

Health status alone does not cause restarts or traffic shifts. Platforms act on health
only when explicitly configured to do so — for example by pointing a liveness or readiness
probe at `/actuator/health`, or configuring a platform health-check policy.

| Status | Meaning | What platforms typically do when configured |
|---|---|---|
| `UP` | Normal | Send traffic |
| `OUT_OF_SERVICE` | App is struggling | Stop routing new requests |
| `DOWN` | App should be restarted | Mark unhealthy; restart or replace |

**Railway example** — add a health check in `railway.json` to let Railway act on the status:

```json
{
  "deploy": {
    "healthcheckPath": "/actuator/health",
    "restartPolicyType": "ON_FAILURE"
  }
}
```

Without such configuration the health indicator is purely informational — visible in
`/actuator/health` and readable by BootLens Server, but no automated action is taken.

### Exposing health details

Spring Boot hides component details by default. To see the per-monitor breakdown shown above:

```properties
management.endpoint.health.show-details=always
# or, to show details only when authenticated:
management.endpoint.health.show-details=when-authorized
```

### Disabling individual monitors vs. the health indicator

Disabling a monitor (`bootlens.client.monitoring.memory-pressure.enabled=false`) removes it from health entirely — its
level source is not registered and it contributes nothing to the aggregate status.

Disabling the health indicator (`bootlens.client.health.enabled=false`) stops all monitor
levels from affecting health, but the monitors themselves keep running and logging.

To disable:

```properties
bootlens.client.health.enabled=false
```

## Endpoint Exposure

Expected actuator paths:

- `GET /actuator/bootlensDiagnostics`
- `GET /actuator/bootlensDiagnostics/operations`
- `POST /actuator/bootlensDiagnostics/{operation}`
- `GET /actuator/bootlensDiagnostics/heap-dumps/{id}` when heap dump download is enabled

- `GET  /actuator/bootlensProfiler` — status (`state`, `activeInterval`, remaining seconds, last session info)
- `POST /actuator/bootlensProfiler` — start session (`event`, `durationSeconds`, `format`, `interval`)
- `DELETE /actuator/bootlensProfiler` — stop active session
- `GET  /actuator/bootlensProfiler/flat[?limit=N]` — top-N hottest methods (text)
- `GET  /actuator/bootlensProfiler/traces[?limit=N]` — top-N call traces (text)
- `GET  /actuator/bootlensProfiler/collapsed` — collapsed stacks (FlameGraph / speedscope compatible)
- `GET  /actuator/bootlensProfiler/samples` — sample count collected so far
- `GET  /actuator/bootlensProfiler/version` — async-profiler native library version
- `GET  /actuator/bootlensProfiler/download/{filename}` — download output file

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
# Diagnostics
curl http://localhost:9091/actuator/bootlensDiagnostics
curl http://localhost:9091/actuator/bootlensDiagnostics/operations
curl -X POST http://localhost:9091/actuator/bootlensDiagnostics/THREAD_DUMP
curl -X POST http://localhost:9091/actuator/bootlensDiagnostics/ENV
curl -X POST http://localhost:9091/actuator/bootlensDiagnostics/HEAP_DUMP
curl -O http://localhost:9091/actuator/bootlensDiagnostics/heap-dumps/{id}

# Profiler — status, start, stop, download
curl http://localhost:9091/actuator/bootlensProfiler
curl -X POST http://localhost:9091/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"cpu","durationSeconds":60,"format":"flamegraph"}'
curl -X POST http://localhost:9091/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"alloc","durationSeconds":30,"interval":"512k"}'
curl -X POST http://localhost:9091/actuator/bootlensProfiler \
  -H 'Content-Type: application/json' \
  -d '{"event":"wall","durationSeconds":60,"interval":"50ms"}'
curl http://localhost:9091/actuator/bootlensProfiler/flat
curl http://localhost:9091/actuator/bootlensProfiler/samples
curl -X DELETE http://localhost:9091/actuator/bootlensProfiler
curl -O http://localhost:9091/actuator/bootlensProfiler/download/bootlens-abc123def456.html
```

## Runtime Side Effects

When this starter is on the classpath the following happen automatically without explicit configuration:

| Action | Thread / resource | Opt-out property |
|---|---|---|
| HTTP POST to BootLens server on startup | `bootlens-registration-heartbeat` daemon thread | `bootlens.client.registration.enabled=false` |
| HTTP POST heartbeat every 10 seconds | Same thread | `bootlens.client.registration.enabled=false` |
| HTTP DELETE on graceful shutdown | Shutdown hook | `bootlens.client.registration.deregister-on-shutdown=false` |
| Memory pressure monitor poll every 60 s | Daemon thread | `bootlens.client.monitoring.memory-pressure.enabled=false` |
| GC pause monitor poll every 60 s | Daemon thread | `bootlens.client.monitoring.gc-pressure.enabled=false` |
| File descriptor monitor poll every 60 s | Daemon thread | `bootlens.client.monitoring.file-descriptors.enabled=false` |
| Direct memory monitor poll every 60 s | Daemon thread | `bootlens.client.monitoring.direct-memory.enabled=false` |
| Thread deadlock detector poll every 60 s | Daemon thread | `bootlens.client.monitoring.thread-deadlock.enabled=false` |
| **Log error rate monitor — attaches a Logback appender to the root logger** | Daemon thread | `bootlens.client.monitoring.log-errors.enabled=false` |
| Metaspace monitor poll every 60 s | Daemon thread | `bootlens.client.monitoring.metaspace.enabled=false` |
| async-profiler native library extraction/loading at startup | Startup (no background thread) | `bootlens.client.profiler.enabled=false` |
| `bootlens-profiler-scheduler` daemon thread | Daemon thread | `bootlens.client.profiler.enabled=false` |

**Note on Logback appender:** The log error rate monitor registers a custom appender on the Logback root logger at application readiness. This modifies the logging pipeline. If your application uses Logback and you do not want this side effect, disable the monitor explicitly.

All registration and heartbeat calls are best-effort: BootLens Server unavailability does not fail application startup. The first failure is logged at WARN and subsequent retries at DEBUG.

## Safety Notes

- Sensitive diagnostics are blocked by default.
- Expensive diagnostics can be disabled independently.
- **Expensive operations (`GC_CLASS_HISTOGRAM`, `HEAP_DUMP`, `THREAD_DUMP`, etc.) are rate-limited by default** (`expensive-operation-cooldown=30s`). Set `bootlens.client.diagnostics.expensive-operation-cooldown=PT0S` to disable.
- Heap dump creation is disabled by default and must be explicitly enabled.
- Heap dumps are highly sensitive and may contain secrets, tokens, private data, cached objects, and full in-memory application state.
- Heap dump creation can pause or slow the JVM and heap dump files can be very large.
- Heap dump creation and download should be treated as operator/admin actions in BootLens, not casual browsing features.
- `sanitize-privacy=true` masks local usernames, home directories, temp paths, machine names, local working directories, and other privacy-sensitive runtime values.
- `include-classpath=false` omits `java.class.path` from `SYSTEM_PROPERTIES` output by default because classpaths are often very large and reveal local machine paths.
- Output is sanitized by default for both common secret-like keys and privacy-sensitive runtime values. The sanitizer masks common credential keys including `password`, `token`, `secret`, `api_key`, `url` (covers `DATABASE_URL`, `REDIS_URL`, etc.), `dsn`, `connection`, and more.
- Output is truncated when it exceeds `max-output-chars`.
- **`allow-sensitive=true` must never be enabled permanently in production.** Operations like `ENV` and `SYSTEM_PROPERTIES` can expose database connection strings (e.g. `DATABASE_URL=postgres://user:pass@host/db`). While the sanitizer masks known patterns, treat sensitive output as operator-only tooling.
- For local debugging, you can explicitly enable classpath output with `bootlens.client.diagnostics.include-classpath=true`.
- Heap dump download is only available for heap dumps created by BootLens and only when `bootlens.client.diagnostics.heap-dump.allow-download=true`.
- The starter does not change standard Spring Boot Actuator behavior for existing endpoints.
- **Always use HTTPS** for `bootlens.client.registration.server-url` in production. Registration payloads include the `actuator-password` credential and must not be sent over unencrypted HTTP.
