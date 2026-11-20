plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

fun configuredValue(name: String): String? =
    providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull

fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

// ── Stable signing identity ──────────────────────────────────────────────────
// Past releases were built with `assembleDebug`, and AGP generated a NEW debug keystore on
// every CI run. Each release therefore had a different certificate and Android refused to
// over-install it. This committed keystore is the ONE stable identity from now on. Real
// release secrets (JARVIS_RELEASE_*) still take precedence when configured.
val bundledStoreFile = file("keystore/jarvis-release.jks")
val bundledStorePassword = "jarvis-release"
val bundledKeyAlias = "jarvis"
val bundledKeyPassword = "jarvis-release"
val bundledCertSha256 = "81878421DF41E2E0C038FA0C483768651756DCDA3C520EB8C7CB8909EA7B7C84"

val releaseStorePath = configuredValue("JARVIS_RELEASE_STORE_FILE")
    ?: bundledStoreFile.absolutePath.takeIf { bundledStoreFile.isFile }
val releaseStorePassword = configuredValue("JARVIS_RELEASE_STORE_PASSWORD") ?: bundledStorePassword
val releaseKeyAlias = configuredValue("JARVIS_RELEASE_KEY_ALIAS") ?: bundledKeyAlias
val releaseKeyPassword = configuredValue("JARVIS_RELEASE_KEY_PASSWORD") ?: bundledKeyPassword
val releaseCertSha256 = (configuredValue("JARVIS_RELEASE_CERT_SHA256") ?: bundledCertSha256)
    .replace(":", "")
    .uppercase()
val developerName = configuredValue("JARVIS_DEVELOPER_NAME")?.ifBlank { null } ?: "Roshan (Virus Hacker Team)"
val copyrightOwner = configuredValue("JARVIS_COPYRIGHT_OWNER")?.ifBlank { null } ?: "Tech GPTx"
val verificationEndpoint = configuredValue("JARVIS_VERIFICATION_ENDPOINT").orEmpty()

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 161
        versionName = "1.15.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEVELOPER_NAME", buildConfigString(developerName.ifBlank { "Unverified developer" }))
        buildConfigField("String", "COPYRIGHT_OWNER", buildConfigString(copyrightOwner.ifBlank { "Unconfigured" }))
        buildConfigField("String", "PINNED_RELEASE_CERT_SHA256", buildConfigString(releaseCertSha256))
        buildConfigField("String", "VERIFICATION_ENDPOINT", buildConfigString(verificationEndpoint))
        // Single source of truth for the in-app updater manifest URL (HTTPS only).
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            buildConfigString("https://github.com/red-code-cli/Jarvis/releases/latest/download/update.json")
        )
    }

    signingConfigs {
        create("release") {
            if (releaseStorePath != null) {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        // Keep debug builds on the same stable identity too, so a locally built debug APK
        // can over-install a CI release and vice versa.
        getByName("debug") {
            if (releaseStorePath != null) {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

    // Room exports its schema JSON here so an explicit Migration can be written and
    // verified against the previous version. The files are build inputs for the
    // instrumented migration test (see app/src/androidTest) and belong in version control.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    // Required by :harness-core.
    //  * The Linux runtime is executed from `applicationInfo.nativeLibraryDir`, so the
    //    native libraries must be extracted at install time rather than loaded from the
    //    APK (AGP's non-legacy default).
    //  * Runtime bundles are zstd archives and must stay decompressed-safe in assets.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += "zst"
    }

    lint {
        // AndroidX Lifecycle 2.8.6's NullSafeMutableLiveData detector crashes under this project's Kotlin 2.0 UAST.
        // Keep all other lint checks enabled while that upstream detector is incompatible.
        disable += "NullSafeMutableLiveData"
    }
}

tasks.register("verifyReleaseSecurityInputs") {
    doLast {
        check(releaseStorePath != null) {
            "Release signing is not configured: the bundled stable keystore is missing and no JARVIS_RELEASE_STORE_FILE was provided."
        }
        check(releaseCertSha256.matches(Regex("[A-F0-9]{64}"))) {
            "Set JARVIS_RELEASE_CERT_SHA256 to the SHA-256 fingerprint of the release signing certificate."
        }
        check(developerName.isNotBlank() && copyrightOwner.isNotBlank()) {
            "Set JARVIS_DEVELOPER_NAME and JARVIS_COPYRIGHT_OWNER to the verified publisher identity."
        }
    }
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    dependsOn("verifyReleaseSecurityInputs")
}

// Writes the exported Room schema JSON into app/schemas so a future schema change can
// ship an explicit Migration checked against the previous version. Room only warns when
// this is missing, but without it no migration can be written or tested.
kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":harness-core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.webkit:webkit:1.12.0")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // sherpa-onnx (Apache-2.0): offline keyword spotting + speaker embedding for the
    // on-device wake word engine and owner voice verification. Served from JitPack.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.5")
    // Pure-Java bz2 + tar so wake models can be extracted on device (no native lib).
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("com.google.zxing:core:3.5.3")

    // Shizuku privileged APIs (latest stable on Maven Central)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Security Crypto for EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager: battery-friendly periodic (6h, network-connected) update check.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")

    // Instrumented Room migration test. The PR check compiles androidTest but never runs
    // it; running it needs a device/emulator. MigrationTestHelper reads the exported
    // schema from app/schemas. Versions stay on the compileSdk 34 line.
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}


