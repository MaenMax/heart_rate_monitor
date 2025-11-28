# Keep camera and analysis classes
-keep class androidx.camera.** { *; }
-keep class com.example.heartratemonitor.** { *; }

# Keep tone generator
-keep class android.media.ToneGenerator { *; }

# CameraX
-dontwarn androidx.camera.**

# Lottie
-dontwarn com.airbnb.lottie.**
-keep class com.airbnb.lottie.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# OkIO (used by Lottie)
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okio.**
-keep class okio.** { *; }

# Missing annotations
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault

