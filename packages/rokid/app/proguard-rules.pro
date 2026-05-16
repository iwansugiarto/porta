# Add project specific ProGuard rules here.
-keep class com.porta.rokid.shared.protocol.** { *; }
-keep class com.porta.rokid.data.** { *; }
-keep class okhttp3.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
