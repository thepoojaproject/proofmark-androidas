# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   https://developer.android.com/studio/build/shrink-code

# Keep the WebView JavaScript bridge that receives file downloads
# (blob:/data: URLs) from the page so R8 doesn't strip or rename the
# methods it calls via addJavascriptInterface.
-keepclassmembers class com.proofmark.qrstudio.AndroidDownloader {
    public *;
}

# Keep WebViewAssetLoader and its path handlers intact — they're resolved
# reflectively in a few AndroidX Webkit code paths.
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

# Keep Kotlin metadata needed for reflection-based AndroidX components.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature

# Standard Android/Kotlin coroutine and parcelable keep rules.
-keep class kotlin.coroutines.Continuation

-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Suppress notes about duplicate/missing classes coming from optional
# desugaring and support libraries pulled in transitively.
-dontnote **
