# Sankofa Android SDK

Native Android analytics and session replay SDK for the Sankofa platform.

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

**Manual masking** – tag any `View` to mask it:
```kotlin
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
