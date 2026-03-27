# Sankofa Android SDK 🚀

[![Maven Central](https://img.shields.io/maven-central/v/dev.sankofa.sdk/sankofa-android)](https://central.sonatype.com/artifact/dev.sankofa.sdk/sankofa-android)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Sankofa](https://img.shields.io/badge/Made%20with-Sankofa-blueviolet)](https://sankofa.dev)

The official Android SDK for [Sankofa Analytics](https://sankofa.dev). Capture every event, resolve user identities, and experience high-fidelity session replays with a single, lightweight package.

---

## ✨ Features

- **Event Tracking**: Send custom events with arbitrary properties and automatic device metadata.
- **Identity Management**: Seamlessly link anonymous users to permanent customer profiles.
- **Session Replay**: 
  - **Screenshot Mode**: Pixel-perfect visual capture for complex UI debugging.
  - **Auto-masking**: Sensitive data protection via `sankofaMask` extension and automatic `EditText` detection.
- **Offline Reliability**: Robust local queueing with background auto-flushing via WorkManager.
- **Privacy First**: Choose what to track and what to mask with granular controls.

---

## 🚀 Quick Start

### 1. Install
Add the dependency to your module's `build.gradle.kts` (or `build.gradle`):

#### Gradle (Kotlin)
```kotlin
dependencies {
    implementation("dev.sankofa.sdk:sankofa-android:1.0.0")
}
```

#### Gradle (Groovy)
```groovy
dependencies {
    implementation 'dev.sankofa.sdk:sankofa-android:1.0.0'
}
```

#### Maven
```xml
<dependency>
  <groupId>dev.sankofa.sdk</groupId>
  <artifactId>sankofa-android</artifactId>
  <version>1.0.0</version>
  <type>aar</type>
</dependency>
```

### 2. Initialize
Initialize the SDK in your `Application` class. It **auto-registers** to all Activities – no per-screen boilerplate required.

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Sankofa.init(
            context = this,
            apiKey = "YOUR_PROJECT_API_KEY",
            config = SankofaConfig(
                recordSessions = true,
                maskAllInputs = true, // Auto-mask all EditTexts
            )
        )
    }
}
```

---

## 🛠 Usage Guide

### Event Tracking
Track any user action with a simple method call.

```kotlin
Sankofa.track("completed_purchase", mapOf(
    "item_name" to "Vintage Camera",
    "price" to 120.50,
    "currency" to "USD"
))
```

### Identity & Profiles
Identify your users to merge their anonymous history into a single profile.

```kotlin
// Link anonymous data to a specific user ID
Sankofa.identify("user_99")

// Set user attributes
Sankofa.setPerson(
    name = "Jane Doe",
    email = "jane@example.com",
    properties = mapOf("membership" to "Gold")
)
```

### Session Replay Privacy
Sankofa prioritizes user privacy. You can mask sensitive UI elements manually using either a Kotlin extension or a View tag.

```kotlin
// Option 1: Kotlin Extension (Recommended)
myView.sankofaMask = true

// Option 2: View Tag (Works for XML and Java)
myView.tag = "sankofa_mask"
```

---

## 🏗 Modular Architecture (For Contributors)

The Sankofa Android SDK is built with a modular, highly-testable architecture:

- **`Sankofa`**: The primary singleton entry point for initialization and public API dispatching.
- **`EventQueueManager`**: Manages the persistent Room database and background flushing logic.
- **`SyncWorker`**: Utilizes Android **WorkManager** for reliable background sync, even if the app is closed.
- **`ReplayRecorder`**: Captures screen changes using **PixelCopy (API 26+)** with a software canvas fallback.
- **`BitmapPool`**: High-performance memory management using reusable Bitmaps to prevent GC pressure.
- **`MaskTraversal`**: Post-processing engine that applies privacy masks to the captured frames.
- **`ReplayUploader`**: Handles WebP-compressed and Gzipped chunk uploads via a non-blocking Kotlin Channel.

### Local Development

1. Clone the repo: `git clone https://github.com/Sankofa-HQ/sankofa_sdk_android`
2. Run build: `./gradlew assembleRelease`
3. Run tests: `./gradlew test` (No emulator required)

---

## 📑 Documentation

For full API references and integration guides, visit our [Documentation Portal](https://docs.sankofa.dev).

---

## 🛡 License

Distributed under the MIT License. See `LICENSE` for more information.
