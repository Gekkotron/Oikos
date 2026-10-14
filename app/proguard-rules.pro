# kotlinx.serialization: keep the generated $$serializer companions for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.gekkotron.oikos.**$$serializer { *; }
-keepclassmembers class com.gekkotron.oikos.** {
    *** Companion;
}
-keepclasseswithmembers class com.gekkotron.oikos.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# HiveMQ / Netty: Netty reflects on handler classes and loads SPI handlers via ServiceLoader.
-dontwarn io.netty.**
-keep class io.netty.** { *; }
-keep class com.hivemq.** { *; }
-keep class org.jctools.** { *; }

# HiveMQ uses SLF4J if available; the app ships none, so silence the optional reference.
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory

# jctools (via Netty) carries OSGi metadata we don't ship.
-dontwarn org.osgi.annotation.bundle.Export

# OkHttp platform detection and conscrypt/BouncyCastle optional providers.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Firebase Crashlytics keeps line numbers for deobfuscated stack traces.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
