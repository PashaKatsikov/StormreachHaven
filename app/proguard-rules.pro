# ══ Attribute preservation ═════════════════════════════════════════════════
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod
-keepattributes InnerClasses

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

# ══ Native (white) game — R8 cannot prove the game Activities are live ═════
-keep class com.stormreachhaven.stormreachgame.MainActivity { *; }
-keep class com.stormreachhaven.stormreachgame.LegalActivity { *; }

# ══ Strip debug-level logs in release ══════════════════════════════════════
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
