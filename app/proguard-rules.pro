# Native ASR bridge — JNI calls these by name/signature.
-keep class com.qwenasr.** { *; }
-keepclasseswithmembernames class com.qwenasr.** {
    native <methods>;
}
# Listener implementations are invoked from native code.
-keep class * implements com.qwenasr.QAsrEngine$VadListener { *; }
