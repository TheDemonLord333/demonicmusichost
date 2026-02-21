# Add project specific ProGuard rules here.

# Keep model classes for Firebase serialization
-keep class com.demonicmusichost.app.data.model.** { *; }

# Keep Retrofit interfaces
-keep interface com.demonicmusichost.app.data.network.** { *; }

# Retrofit & OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.examples.android.model.** { <fields>; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }

# Spotify SDK
-keep class com.spotify.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# ZXing (QR codes)
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }
