plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.sankofa.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        aarMetadata {
            minCompileSdk = 21
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)

    // Room – local event queue (offline-first)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager – background sync engine
    implementation(libs.work.runtime.ktx)

    // OkHttp – network client for batch upload
    implementation(libs.okhttp)

    // Gson – JSON serialization
    implementation(libs.gson)

    // Lifecycle – ProcessLifecycleOwner for background detection
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.common.java8)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
