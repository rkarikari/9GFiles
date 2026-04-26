# File Manager Pro ProGuard Rules

# Keep application class
-keep class com.filemanager.pro.FileManagerApp { *; }

# Keep all model classes (Parcelable, Room entities)
-keep class com.filemanager.pro.data.model.** { *; }
-keep class com.filemanager.pro.data.db.** { *; }

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# Apache Commons Compress
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.**

# Navigation Component
-keep class androidx.navigation.** { *; }

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.InputMerger
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(...);
}

# DataStore
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ── javax.annotation (missing from Android runtime, pulled in by Tink via security-crypto) ──
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.CheckForNull
-dontwarn javax.annotation.concurrent.GuardedBy
-dontwarn javax.annotation.concurrent.ThreadSafe
-dontwarn javax.annotation.concurrent.Immutable

# ── Google Tink (transitive dep of androidx.security:security-crypto) ──
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**

# ── androidx.security:security-crypto ──
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── androidx.biometric ──
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# ── ExifInterface ──
-keep class androidx.exifinterface.** { *; }
-dontwarn androidx.exifinterface.**

# ── SlidingPaneLayout ──
-keep class androidx.slidingpanelayout.** { *; }
-dontwarn androidx.slidingpanelayout.**

# ── Markwon ──
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# ── Apache Commons Net (FTP) ──
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**

# ── App package ──
-keep class com.radiozport.ninegfiles.** { *; }

# ══════════════════════════════════════════════════════════════
# NEW LIBRARY RULES (jcifs-ng, jsch, zip4j, slf4j-android)
# ══════════════════════════════════════════════════════════════

# ── SLF4J (logging facade pulled in by jcifs-ng & jsch) ──────
# StaticLoggerBinder is the class that was making R8 fail.
# slf4j-android provides it at runtime; the dontwarn covers any
# remaining optional references R8 cannot resolve.
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.slf4j.impl.**

# ── jcifs-ng (SMB) ───────────────────────────────────────────
-keep class jcifs.** { *; }
-keep class eu.agno3.jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn eu.agno3.**
# jcifs-ng optionally uses javax.naming (JNDI) and SPNEGO/GSSAPI
-dontwarn javax.naming.**
-dontwarn javax.security.auth.**
-dontwarn javax.security.sasl.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.jndi.**

# ── JSch (SFTP) ───────────────────────────────────────────────
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
# JSch optionally references com.jcraft.jzlib for compression
-dontwarn com.jcraft.jzlib.**
# JSch optionally uses JCE provider API
-dontwarn javax.crypto.**

# ── zip4j (password-protected ZIP) ───────────────────────────
-keep class net.lingala.zip4j.** { *; }
-dontwarn net.lingala.zip4j.**

# ── Commons Net (FTP) — already present but ensure keep ──────
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**

# ── Google Cast framework ─────────────────────────────────────
# The Cast SDK uses reflection to load the OptionsProvider class
# referenced by the manifest meta-data key
# com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME.
# Without these rules R8 strips the class and Cast silently fails.
-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.cast.framework.** { *; }
-keep class com.radiozport.ninegfiles.CastOptionsProvider { *; }
-dontwarn com.google.android.gms.cast.**
