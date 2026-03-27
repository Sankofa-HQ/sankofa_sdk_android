plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    id("maven-publish")
    id("signing")
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
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

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "dev.sankofa.sdk"
                artifactId = "sankofa-android"
                version = "1.0.0"

                // FIX 1: Removed the nested afterEvaluate
                from(components["release"])

                pom {
                    name.set("Sankofa Android SDK")
                    description.set("The official Android SDK for Sankofa. Provides privacy-first session replay, offline-ready event tracking, and advanced mobile observability.")
                    url.set("https://sankofa.dev")

                    licenses {
                        license {
                            name.set("The MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("saytoonz")
                            name.set("Sankofa")
                            email.set("dev@sankofa.dev")
                        }
                    }
                    scm {
                        connection.set("scm:git:github.com/Sankofa-HQ/sankofa_sdk_android.git")
                        developerConnection.set("scm:git:ssh://github.com/Sankofa-HQ/sankofa_sdk_android.git")
                        url.set("https://github.com/Sankofa-HQ/sankofa_sdk_android")
                    }
                }
            }
        }
        
        // FIX 2: Switched back to the bulletproof Local Bundle method
        repositories {
            maven {
                name = "LocalBundle"
                url = uri(layout.buildDirectory.dir("repo"))
            }
        }
    }

    signing {
        sign(publishing.publications["release"])
    }
}
