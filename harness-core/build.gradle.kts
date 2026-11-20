plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Application id of the consuming app. It is used for exactly one thing: compiling the
// absolute path of the app's private cache directory, which libandroid-shmem needs at
// build time. Override for another app with -PharnessApplicationId=<applicationId>.
val harnessApplicationId: String =
    (providers.gradleProperty("harnessApplicationId").orNull ?: "com.jarvis.assistant").trim()

// Mobile Harness pins NDK 28+: earlier NDKs ignored --max-page-size and produced 4 KiB
// segments that crash on 16 KiB Android runtimes. Override with -PharnessNdkVersion.
val harnessNdkVersion: String =
    providers.gradleProperty("harnessNdkVersion").orNull ?: "28.2.13676358"

android {
    namespace = "com.jarvis.harness"
    // The engine's Kotlin only uses APIs up to Android 13, so the module compiles against
    // the host app's compileSdk expectation (Jarvis: 34) without forcing an app bump.
    compileSdk = 34
    ndkVersion = harnessNdkVersion

    defaultConfig {
        minSdk = 26

        // PRoot only has an arm64-v8a implementation. Devices without it must hide the
        // coding-engine UI; the engine itself reports unsupported via supportsArm64Runtime().
        ndk {
            abiFilters += "arm64-v8a"
        }

        consumerProguardFiles("proguard-rules.pro")

        externalNativeBuild {
            cmake {
                arguments += "-DHARNESS_CACHE_DIR=/data/data/$harnessApplicationId/cache/"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // The engine targets a fixed set of legacy Android entry points; the host app
        // owns user-facing string/localization lint.
        disable += "MissingTranslation"
    }
}

dependencies {
    // Versions are aligned with the Jarvis app so adding this module does not change the
    // host app's resolved dependency graph.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.7-12@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
}
