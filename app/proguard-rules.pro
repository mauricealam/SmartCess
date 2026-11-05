# SmartCess ProGuard rules (release minification).
-keep class com.smartcess.app.** { *; }
-keep class androidx.security.crypto.** { *; }
# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.internal.ComponentManager { *; }
# Room: keep entities/DAOs (added by later agents under data.local)
-keep class androidx.room.** { *; }
# kotlinx.serialization: keep serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
# CameraX: keep public API surface used via reflection
-dontwarn androidx.camera.**
