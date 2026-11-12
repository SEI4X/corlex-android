# Corlex for Android

Version 0.1.0 is an unpublished SDK-only source candidate. Stable package/tag publication and owner merge are separate release gates. The Corlex application/server and its private Git history are not part of this repository.

## Installation

From a reviewed checkout, add `includeBuild("../corlex-android")` to the host settings.gradle.kts with dependency substitution `ru.corlex:corlex` → `:corlex` and optional `ru.corlex:corlex-compose` → `:corlex-compose`. Use `implementation("ru.corlex:corlex:0.1.0")`. Keep google() and mavenCentral(). No JitPack publication is assumed.

Use the project's actual ingest/config URLs. The write-only project SDK key is compiled into the client; it is observable in an app bundle. MCP, upload and AI-provider secrets must never be embedded in an app. Corlex MCP onboarding can provision the SDK key directly into ignored build configuration.

## Quick start

The variables below come from your host app's ignored build configuration/project setup; they are not literal placeholder services.

```kotlin
val options = CorlexOptions(
    endpoint = ingestUrl, configEndpoint = configUrl, consent = Consent.PENDING,
)
Corlex.start(application, BuildConfig.CORLEX_KEY, options)
// After the host app has obtained the applicable consent:
Corlex.optIn()
Corlex.track("signup_completed", mapOf("method" to "email"))
```

Call startup once. Never copy another analytics pipeline merely to obtain native upload tools. reset rotates identity; historical events retain their captured identities. optOut withdraws collection and clears pending SDK state.

## Features and API

`Corlex.start(context,key,options)`, `track`, `identify`, `setUserProperties`, `screen`, `reset`, `flush`, `optIn`, `optOut`, `consent`, `flag`, `experiment`; `Corlex.config.get/getAll/awaitReady/refresh/onUpdate`.

Automatic lifecycle and screen events, explicit product events, consent, offline delivery, crash reports, Remote Config and deterministic experiments are described in [API](docs/API.md), [data/consent](docs/CONSENT-AND-DATA.md), [storage](docs/LOCAL-STORAGE.md) and [crashes](docs/CRASHES.md).

## Development

Java 17, Android SDK 35, SDK platform/build tools and accepted SDK licenses. `./gradlew --no-daemon --max-workers=2 --dependency-verification strict check :example:assembleRelease`; `./gradlew --no-daemon --max-workers=2 -p gradle-plugin test`. Dependency checksums/locks must be reviewed, not silently regenerated in CI.

See [troubleshooting](docs/TROUBLESHOOTING.md), [security](SECURITY.md), [contributing](CONTRIBUTING.md), [release process](docs/PUBLISHING.md) and [changelog](CHANGELOG.md). MIT covers our SDK code; bundled third-party tools/dependencies keep their own licenses/notices.
