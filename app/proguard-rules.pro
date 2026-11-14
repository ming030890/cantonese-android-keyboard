# Native exports use the original JVM class and method names.
# Ordinary Kotlin members remain eligible for shrinking and optimization.
-keep,allowoptimization class com.qwenasr.QAsrEngine { native <methods>; }
-keep,allowoptimization class com.qwenasr.QAsrEngine$Companion { native <methods>; }

# Native code invokes these interface methods by name on the supplied object.
-keep,allowoptimization interface com.qwenasr.QAsrEngine$VadListener { *; }
-keepclassmembers,allowoptimization class * implements com.qwenasr.QAsrEngine$VadListener {
    public void onTranscriptDelta(java.lang.String);
    public void onHypothesisChanged(java.lang.String);
    public void onTranscriptComplete();
    public void onTranscriptError();
}

# Play Asset Delivery uses WorkManager. Room resolves this generated database
# implementation by name; preserve that reflection boundary, not entire packages.
-keepnames class androidx.work.impl.WorkDatabase
-keep,allowoptimization class androidx.work.impl.WorkDatabase_Impl { public <init>(); }
