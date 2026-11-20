# :harness-core consumer rules.
# Shipped via consumerProguardFiles so any host app that enables R8 keeps the engine
# entry points intact without having to copy these rules by hand.

# Public facade reached from host-app glue and from service/receiver entry points.
-keep class com.jarvis.harness.HarnessEngine { *; }
-keep class com.jarvis.harness.HarnessEngine$* { *; }

# Android entry points declared in this module's manifest (instantiated by the framework).
-keep class com.jarvis.harness.runtime.RuntimeExecutionService { *; }
-keep class com.jarvis.harness.runtime.RuntimeSetupService { *; }
-keep class com.jarvis.harness.runtime.AndroidAppInstallReceiver { *; }
-keep class com.jarvis.harness.backup.BackupService { *; }

# JNI: pocket_spawn.c exports these exact names and must not be renamed.
-keepclasseswithmembernames class com.jarvis.harness.runtime.NativeSpawnProcess {
    native <methods>;
}
-keep class com.jarvis.harness.runtime.NativeSpawnProcess$NativeSpawn { *; }

# zstd-jni (JNI heavy).
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# Apache Commons Compress uses reflection for some codecs.
-dontwarn org.apache.commons.compress.**
-keep class org.apache.commons.compress.** { *; }

# Kotlin coroutines internals referenced reflectively by the engine's flow plumbing.
-dontwarn kotlinx.coroutines.**

# Engine model classes are persisted as JSON and read back by field name.
-keep class com.jarvis.harness.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
