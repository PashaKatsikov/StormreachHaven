# ══ Attribute preservation ═════════════════════════════════════════════════
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod
-keepattributes InnerClasses
# Stack traces stay readable via mapping.txt, but the APK does not carry
# the original source file names.
-renamesourcefileattribute SourceFile

# ══ WebView JS bridge ══════════════════════════════════════════════════════
# The @JavascriptInterface methods are called by name from JS; R8 must not
# rename them or strip them.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ══ Firebase ═══════════════════════════════════════════════════════════════
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# ══ AppsFlyer ══════════════════════════════════════════════════════════════
-keep class com.appsflyer.** { *; }
-keep class com.android.installreferrer.** { *; }
-dontwarn com.appsflyer.**

# ══ OkHttp ═════════════════════════════════════════════════════════════════
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keep class okhttp3.** { *; }

# ══ Security-crypto ═══════════════════════════════════════════════════════
-keep class androidx.security.crypto.** { *; }

# ══ Gray-flow entry classes (rebrand.py updates the package path here) ═════
-keep class com.stormreachhaven.stormreachgame.screens.FcmReef
-keep class com.stormreachhaven.stormreachgame.config.TideRouter
-keep class com.stormreachhaven.stormreachgame.config.ReefApp

# ══ Native game (white) part ════════════════════════════════════════════════
# Manifest components: keep the class (AGP also keeps it from the manifest)
# but NOT its members, so R8 still renames the game code.
-keep class com.stormreachhaven.stormreachgame.MainActivity
-keep class com.stormreachhaven.stormreachgame.LegalActivity

# ══ Native routing gate (JNI) ══════════════════════════════════════════════
# The class + method names form the Java_com_..._NativeGate_pack/decide symbol
# in libgate.so; R8 must not rename or strip them.
-keep class com.stormreachhaven.stormreachgame.NativeGate { *; }
-keepclasseswithmembernames class * { native <methods>; }

# RunChannel is persisted by its enum constant name (valueOf). Renaming
# UNDECIDED / STREAM / NATIVE would make a stored value unreadable.
-keepclassmembernames enum com.stormreachhaven.stormreachgame.prefs.Prefs$RunChannel { *; }

# ══ Strip debug-level logs in release ══════════════════════════════════════
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
