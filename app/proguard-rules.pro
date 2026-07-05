# Native ASR bridge — JNI calls these by name/signature.
-keep class com.qwenasr.** { *; }
-keepclasseswithmembernames class com.qwenasr.** {
    native <methods>;
}
# Listener implementations are invoked from native code.
-keep class * implements com.qwenasr.QAsrEngine$VadListener { *; }

# Play Asset Delivery pulls in WorkManager, which initializes before the app's
# Activity. Room creates WorkDatabase_Impl reflectively, so keep these internals
# stable in minified release builds.
-keep class androidx.work.** { *; }
-keep class androidx.room.** { *; }
-keep class androidx.sqlite.** { *; }
