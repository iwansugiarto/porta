# ============================================================
# Porta Glasses HUD — ProGuard / R8 Rules
# ============================================================

# ---- MainActivity ----
-keep class id.infinia.porta.glasses.MainActivity { *; }

# ---- Gson serialization classes ----
# Keep SubagentEntry data class used with Gson
-keep class id.infinia.porta.glasses.MainActivity$SubagentEntry { *; }
-keepclassmembers class id.infinia.porta.glasses.MainActivity$SubagentEntry {
    <fields>;
    <init>(...);
}

# General Gson keep rules — prevent stripping of fields used in serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- Jetpack Compose ----
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ---- Kotlin Coroutines ----
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---- Kotlin metadata (needed for reflection) ----
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }

# ---- Missing optional dependencies (safe to suppress) ----
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
