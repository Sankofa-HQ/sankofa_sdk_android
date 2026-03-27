# Sankofa Android SDK 🚀

[![Maven Central](https://img.shields.io/maven-central/v/dev.sankofa.sdk/sankofa-android)](https://central.sonatype.com/artifact/dev.sankofa.sdk/sankofa-android)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

The official Android SDK for [Sankofa Analytics](https://sankofa.dev). Capture every event, resolve user identities, and experience high-fidelity session replays with a single, lightweight package.

## ✨ Features

*   **Event Tracking**: Send custom events with arbitrary properties and automatic device metadata.
*   **Identity Management**: Seamlessly link anonymous users to permanent customer profiles.
*   **Session Replay**:
    *   **Screenshot Mode**: Pixel-perfect visual capture for complex UI debugging.
    *   **Auto-masking**: Sensitive data protection via `SankofaMask` and automatic `EditText` detection.
*   **Offline Reliability**: Robust local queueing with background auto-flushing via WorkManager.
*   **Privacy First**: Choose what to track and what to mask with granular controls.
*   **Lightweight**: Minimal footprint, optimized for performance and battery life.

##  Installation

Add the dependency to your module's `build.gradle.kts` (or `build.gradle`):

### Gradle (Kotlin)
```kotlin
dependencies {
    implementation("dev.sankofa.sdk:sankofa-android:1.0.0")
}
```

### Gradle (Groovy)
```groovy
dependencies {
    implementation 'dev.sankofa.sdk:sankofa-android:1.0.0'
}
```

### Maven
```xml
<dependency>
  <groupId>dev.sankofa.sdk</groupId>
  <artifactId>sankofa-android</artifactId>
  <version>1.0.0</version>
  <type>aar</type>
</dependency>
```

## Quick Start

Add to your `Application` class:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Sankofa.init(
            context = this,
            apiKey = "sk_live_12345",
            config = SankofaConfig(
                recordSessions = true,
                maskAllInputs = true,
            )
        )
    }
}
```

That's it. The SDK **auto-registers** into every Activity via `ActivityLifecycleCallbacks` – no per-screen boilerplate required.

---

## API Reference

| Method | Description |
|---|---|
| `Sankofa.init(context, apiKey, config)` | Initialize the SDK (call once in `Application.onCreate`) |
| `Sankofa.track(eventName, properties)` | Track a custom event |
| `Sankofa.identify(userId)` | Link anonymous session to a known user |
| `Sankofa.reset()` | Clear user identity, start fresh anonymous session |
| `Sankofa.peopleSet(properties)` | Set profile attributes for the current user |
| `Sankofa.setPerson(name, email, avatar)` | Convenience wrapper for common user traits |
| `Sankofa.flush()` | Force-upload all queued events immediately |
| `Sankofa.shutdown()` | Tear down the SDK (testing/unusual cases only) |

---

## Session Replay Privacy

**Automatic masking** – all `EditText` views are automatically blacked out in recordings when `maskAllInputs = true` (the default).

**Manual masking** – use the Kotlin extension or set the View tag manually:

```kotlin
// Option 1: Kotlin Extension (Recommended)
myView.sankofaMask = true

// Option 2: View Tag (Works with Java)
myView.tag = "sankofa_mask"
```

---

## Architecture

```
Sankofa.track() → EventQueueManager → Room DB (events_queue)
                                    ↓
                    SyncWorker (WorkManager) → SankofaHttpClient → Go backend
                    (triggers: 50 events | 30s timer | app background)
```

```
Activity resumed → ReplayRecorder
                   ↓  ViewTreeObserver.OnDrawListener (only fires on change)
                   ↓  PixelCopy (API 26+) / view.draw(canvas) fallback
                   ↓  MaskTraversal (EditText + sankofa_mask)
                   ↓  ReplayCompressor (WebP LOSSY 60 + GZIP)
                   ↓  ReplayUploader → /api/ee/replay/chunk
```

---

## Configuration Options

| Option | Default | Description |
|---|---|---|
| `endpoint` | `https://api.sankofa.dev` | Base URL of your Sankofa server |
| `recordSessions` | `true` | Enable session replay |
| `maskAllInputs` | `true` | Auto-mask all `EditText` views |
| `debug` | `false` | Verbose Logcat output |
| `trackLifecycleEvents` | `true` | Auto-track `$app_opened/foregrounded/backgrounded` |
| `flushIntervalSeconds` | `30` | How often to flush events while app is foregrounded |
| `batchSize` | `50` | Flush immediately when this many events accumulate |

---

## Requirements

- **minSdk**: 21 (Android 5.0 Lollipop)
- **compileSdk**: 34
- **Permissions**: `INTERNET`, `ACCESS_NETWORK_STATE` (auto-merged from SDK manifest)

---

## Building

```bash
cd sdks/sankofa_sdk_android
./gradlew :sankofa:assembleRelease   # produces sankofa/build/outputs/aar/sankofa-release.aar
./gradlew :sankofa:test              # run unit tests (no emulator required)
```
