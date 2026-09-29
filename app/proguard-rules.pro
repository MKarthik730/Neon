# LifeVault release rules.

# Release builds contain no logging at all (the app does not log, and this strips any library calls).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(...);
}

# kotlinx.serialization: keep generated serializers of our @Serializable models (vault file formats).
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.lifevault.**$$serializer { *; }
-keepclassmembers class com.lifevault.** {
    *** Companion;
}
-keepclasseswithmembers class com.lifevault.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.lifevault.** { *; }

# Tink uses protobuf-lite reflection on generated message fields.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-dontwarn com.google.crypto.tink.integration.**

# Bouncy Castle: only Argon2 is used; ignore optional JDK-only dependencies.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
