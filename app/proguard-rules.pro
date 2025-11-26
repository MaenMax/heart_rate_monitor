# Keep camera and analysis classes
-keep class androidx.camera.** { *; }
-keep class com.example.heartratemonitor.** { *; }

# Keep tone generator
-keep class android.media.ToneGenerator { *; }

# CameraX
-dontwarn androidx.camera.**

