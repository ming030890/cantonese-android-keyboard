# These class/method names form the JNI ABI; other Kotlin code can be optimized.
-keep,allowoptimization class com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine { native <methods>; }
-keep,allowoptimization class com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine { native <methods>; }
-keep,allowoptimization class com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognizer { native <methods>; }
