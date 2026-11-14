import android.content.Context;
import android.util.Log;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;

/** Load the installed minified APK classes and exercise their preserved JNI ABI. */
public final class NativeReleaseSmoke {
    private static byte[] asset(Context context, String path) throws Exception {
        try (InputStream stream = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static Object call(Class<?> type, String name, Class<?>[] parameters, Object... args) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> threadType = Class.forName("android.app.ActivityThread");
        Method systemMain = threadType.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getContext = threadType.getDeclaredMethod("getSystemContext");
        getContext.setAccessible(true);
        Context system = (Context) getContext.invoke(thread);
        Context context = system.createPackageContext(args[0], Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        ClassLoader loader = context.getClassLoader();
        // WorkManager is required by Play Asset Delivery; its database must survive R8.
        Class.forName("androidx.work.impl.WorkDatabase_Impl", true, loader);
        byte[] lm = asset(context, "language_model/model.cng");
        byte[] labels = asset(context, "handwriting/labels.txt");
        byte[] empty = new byte[0];
        Class<?>[] fiveBytes = {byte[].class, byte[].class, byte[].class, byte[].class, byte[].class};
        Log.i("NativeReleaseSmoke", "Loading Quick JNI");
        Class<?> quick = Class.forName("com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine", true, loader);
        long quickHandle = (Long) call(quick, "nativeCreate", fiveBytes,
                asset(context, "quick/quick3.bin"), asset(context, "quick/quick5.bin"), lm, empty, labels);
        if (quickHandle == 0) throw new AssertionError("Quick JNI initialization failed");
        try {
            String[] candidates = (String[]) call(quick, "nativeCandidates",
                    new Class<?>[]{long.class, int.class, String.class, String.class, String.class, boolean.class},
                    quickHandle, 3, "humo", "", "", false);
            if (candidates == null || candidates.length == 0) throw new AssertionError("Quick JNI inference failed");
            Class<?> english = Class.forName("com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine", true, loader);
            String suggestions = (String) call(english, "nativeSuggest",
                    new Class<?>[]{long.class, String.class, String.class}, quickHandle, "I said ", "hello");
            if (suggestions == null || !suggestions.contains("\"candidates\"")) throw new AssertionError("English JNI failed");
        } finally {
            call(quick, "nativeDestroy", new Class<?>[]{long.class}, quickHandle);
        }
        Log.i("NativeReleaseSmoke", "Loading handwriting JNI");
        Class<?> handwriting = Class.forName("com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognizer", true, loader);
        long hwrHandle = (Long) call(handwriting, "nativeCreate", fiveBytes,
                asset(context, "handwriting/model.safetensors"), labels, empty, lm,
                asset(context, "language_model/handwriting_fusion.json"));
        if (hwrHandle == 0) throw new AssertionError("Handwriting JNI initialization failed");
        try {
            String[] candidates = (String[]) call(handwriting, "nativePredictStrokes",
                    new Class<?>[]{long.class, float[].class, int[].class, float.class, float.class, int.class, String.class},
                    hwrHandle, new float[]{12, 10, 0, 24, 24, 10, 36, 40, 20}, new int[]{3}, 48f, 48f, 5, "");
            if (candidates == null || candidates.length == 0) throw new AssertionError("Handwriting JNI inference failed");
        } finally {
            call(handwriting, "nativeDestroy", new Class<?>[]{long.class}, hwrHandle);
        }
        Log.i("NativeReleaseSmoke", "Loading ASR JNI");
        Class<?> asr = Class.forName("com.qwenasr.QAsrEngine", true, loader);
        boolean diagnostics = (Boolean) call(asr, "nativeConfigureDiagnostics", new Class<?>[]{boolean.class}, false);
        if (diagnostics) throw new AssertionError("ASR diagnostics enabled in release");
        System.out.println("{\"passed\":true,\"minified_jni\":[\"quick\",\"english\",\"handwriting\",\"asr_configuration\"],\"workmanager_database_retained\":true}");
        System.exit(0);
    }
}
