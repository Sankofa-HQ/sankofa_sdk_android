# Sankofa Android SDK 🚀

[![Maven Central](https://img.shields.io/maven-central/v/dev.sankofa.sdk/sankofa-android)](https://central.sonatype.com/artifact/dev.sankofa.sdk/sankofa-android)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Sankofa](https://img.shields.io/badge/Made%20with-Sankofa-blueviolet)](https://sankofa.dev)

The official Android SDK for [Sankofa](https://sankofa.dev). Six products in one Kotlin library: Analytics, Catch (Crashlytics + Sentry merged), Switch, Config, Pulse, Replay.

---

## ✨ Features

- **Analytics** — events, identify, peopleSet. WorkManager-backed offline-first queue.
- **Catch** — chained `Thread.UncaughtExceptionHandler` + ANR watcher, auto-installed by `Sankofa.init`. Sentry-style `withScope` + `beforeSend` hooks.
- **Switch** — feature flags with bundled defaults, onChange listeners, halt webhook support.
- **Config** — remote-config with typed `get<T>` accessors.
- **Pulse** — in-app surveys.
- **Session Replay** — screenshot mode, automatic input masking, `sankofaMask` view extension. **Compose-aware** scroll-offset tagging via `Sankofa.tagScrollContainer { ... }`.

---

## 🚀 Quick Start

### 1. Install

```kotlin
dependencies {
    implementation("dev.sankofa.sdk:sankofa-android:1.0.0")
}
```

### 2. Initialize

One line. Catch auto-installs alongside analytics; no separate `SankofaCatch.init(...)` call needed.

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Switch + Config first so the auto-discovered flag/config
        // snapshots Catch attaches to its events on the very first
        // crash already see something useful.
        SankofaSwitch.init(this, mapOf("new_checkout" to false))
        SankofaRemoteConfig.init(this, mapOf("max_uploads_per_day" to 25))

        Sankofa.init(
            context = this,
            apiKey = BuildConfig.SANKOFA_KEY,
            config = SankofaConfig(
                recordSessions = true,
                maskAllInputs = true,
                catchEnvironment = "production",
                release = "myapp@1.4.0",
                appVersion = BuildConfig.VERSION_NAME,
                // Optional Sentry-style hook.
                beforeSend = { event ->
                    if (event.message?.contains("[noise]") == true) null else event
                },
            )
        )

        SankofaPulse.register(applicationContext)
    }
}
```

---

## 🛠 Usage

### Analytics

```kotlin
Sankofa.track("completed_purchase", mapOf(
    "item_name" to "Vintage Camera",
    "price" to 120.50,
))

Sankofa.identify("user_99")
Sankofa.setPerson(name = "Jane Doe", email = "jane@example.com")
```

### Catch — Crashlytics + Sentry merged

Static helpers work from anywhere — no instance to thread through.

```kotlin
// Capture a handled throwable
try {
    chargeCard(amount)
} catch (e: Throwable) {
    Sankofa.captureException(e)
}

// Crashlytics-style breadcrumb log — rides on next capture, doesn't bill.
Sankofa.log("checkout: applying coupon SUMMER25")

// Ambient context
Sankofa.setUser(CatchUserContext(id = "u_42", email = "ada@example.com"))
Sankofa.setTag("flow", "checkout")
Sankofa.setExtra("cart_id", cart.id)

// Sentry-style temporary scope (thread-local — UI vs background captures
// don't see each other's scopes).
Sankofa.withScope { scope ->
    scope.setTag("checkout_step", "payment")
    scope.setLevel(CatchLevel.WARNING)
    Sankofa.captureException(err)
}
```

### Session Replay — masking

```kotlin
myView.sankofaMask = true  // Auto-masks this view in replays
```

### Compose scroll-offset tagging

Compose hosts (`LazyColumn`, `Modifier.verticalScroll`) draw into a single `AndroidComposeView`, so the classic-view walker returns 0 for scroll offset and below-the-fold taps collapse to the first viewport in heatmaps. Register a Compose provider:

```kotlin
@Composable
fun ProductList() {
    val scrollState = rememberScrollState()
    DisposableEffect(scrollState) {
        val handle = Sankofa.tagScrollContainer { scrollState.value }
        onDispose { handle.remove() }
    }
    Column(modifier = Modifier.verticalScroll(scrollState)) { /* ... */ }
}
```

For `LazyColumn`:

```kotlin
val handle = Sankofa.tagScrollContainer {
    listState.firstVisibleItemIndex * estimatedItemHeightPx +
        listState.firstVisibleItemScrollOffset
}
```

### Switch / Config / Pulse

```kotlin
if (SankofaSwitch.getFlag("new_checkout")) showNewCheckout()
val maxUploads = SankofaRemoteConfig.get<Int>("max_uploads_per_day", 25)
SankofaPulse.show(activity, surveyId = "nps-2024")
```

---

## 📑 Documentation

Full API reference and integration guides: [docs.sankofa.dev/sdks/android](https://docs.sankofa.dev/sdks/android/overview).

---

## 🛡 License

Distributed under the MIT License. See `LICENSE` for more information.
