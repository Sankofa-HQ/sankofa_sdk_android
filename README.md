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
Mask sensitive UI elements from session replays.

```kotlin
myView.sankofaMask = true
```

---

## 📑 Documentation

For full API references and integration guides, visit our [Documentation Portal](https://docs.sankofa.dev).

---

## 🛡 License

Distributed under the MIT License. See `LICENSE` for more information.
