# Add project specific ProGuard rules here.
-keep class id.infinia.porta.shared.protocol.** { *; }
-keep class id.infinia.porta.data.** { *; }
-keep class okhttp3.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
# Android Auto — service disabled in manifest but classes still compiled
-dontwarn androidx.car.app.**
