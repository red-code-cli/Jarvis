# Keep Gson model classes
-keep class com.jarvis.assistant.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep JavascriptInterfaces for WebViews (Cyberpunk Orb & Settings Activity)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep JNA native libraries & models
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class net.java.dev.jna.** { *; }
# JNA ships optional desktop AWT bridges that are not present on Android; app code does not use them.
-dontwarn java.awt.**

# Keep Room database models
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase


# ── Tech GPTx Security Module — keep rules ───────────────────────────────────
-keep class com.jarvis.assistant.security.SecurityManager { *; }
-keep class com.jarvis.assistant.security.DeveloperInfo { *; }
-keep class com.jarvis.assistant.security.AppIdentityVerifier { *; }

# ── Shizuku privileged integration — keep rules ──────────────────────────────
-keep class com.jarvis.assistant.privilege.PrivilegedUserService { *; }
-keep class com.jarvis.assistant.privilege.PrivilegeManager { *; }
-keep class com.jarvis.assistant.privilege.IPrivilegedService { *; }
-keep class com.jarvis.assistant.privilege.IPrivilegedService$Stub { *; }
-keep class com.jarvis.assistant.privilege.IPrivilegedService$Stub$Proxy { *; }
-keep class rikka.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# Obfuscate everything else aggressively
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'tgptx'

# ── sherpa-onnx (JNI) ────────────────────────────────────────────────────────
# Native methods are bound by class + method name, so R8 must not rename or strip them.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class com.k2fsa.sherpa.onnx.** { native <methods>; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Remove Log calls in release builds (security: don't leak internals)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# ── In-App Auto Update ───────────────────────────────────────────────────────
# WorkManager instantiates UpdateCheckWorker by class name and the manifest references
# UpdateActivity by name; -repackageclasses would otherwise break those lookups.
-keep class com.jarvis.assistant.update.UpdateCheckWorker { *; }
-keep class com.jarvis.assistant.update.UpdateActivity { <init>(...); }
-keep class com.jarvis.assistant.update.UpdateManifest { *; }
-keepclassmembers class com.jarvis.assistant.update.** { *; }
