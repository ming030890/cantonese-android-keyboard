#include <jni.h>

#include <chrono>
#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>

#if HAVE_QWEN_ASR
extern "C" {
#include "qwen_asr.h"
#include "qwen_asr_kernels.h"
}
#endif

namespace {
constexpr const char* kTag = "QwenAsrJni";
std::mutex g_error_mutex;
std::string g_last_error;

void set_last_error(const std::string& message) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", g_last_error.c_str());
}

std::string get_last_error() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return g_last_error;
}

using SteadyClock = std::chrono::steady_clock;

int64_t elapsed_ns(const SteadyClock::time_point& start, const SteadyClock::time_point& end) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count();
}

jstring to_jstring(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

#if HAVE_QWEN_ASR
struct NativeEngine {
    qwen_ctx_t* ctx = nullptr;
    std::string forced_language;
    std::mutex lock;
};

struct NativeTrace {
    std::string text;
    int sample_count = 0;
    int64_t input_copy_ns = 0;
    int64_t native_inference_ns = 0;
    int64_t total_ns = 0;
};

std::string normalize_language_for_qwen(const std::string& value) {
    if (value.empty()) return "";

    if (value == "yue" || value == "zh-HK" || value == "zh-hk" || value == "cantonese") {
        return "Cantonese";
    }
    if (value == "zh" || value == "chinese") return "Chinese";
    if (value == "en" || value == "english") return "English";
    if (value == "ja" || value == "japanese") return "Japanese";
    if (value == "ko" || value == "korean") return "Korean";

    return value;
}

void apply_force_language_override(NativeEngine* engine, jboolean force_language) {
    if (force_language == JNI_FALSE) {
        qwen_set_force_language(engine->ctx, "");
    }
}

void restore_force_language_override(NativeEngine* engine, jboolean force_language) {
    if (force_language == JNI_FALSE && !engine->forced_language.empty()) {
        qwen_set_force_language(engine->ctx, engine->forced_language.c_str());
    }
}

NativeTrace run_transcription(
    JNIEnv* env,
    NativeEngine* engine,
    jfloatArray pcm16k_mono,
    jboolean force_language,
    bool streaming
) {
    NativeTrace trace;
    const auto total_start = SteadyClock::now();

    const jsize length = env->GetArrayLength(pcm16k_mono);
    trace.sample_count = static_cast<int>(length);
    if (length <= 0) {
        trace.total_ns = elapsed_ns(total_start, SteadyClock::now());
        return trace;
    }

    const auto copy_start = SteadyClock::now();
    std::vector<float> samples(static_cast<size_t>(length));
    env->GetFloatArrayRegion(pcm16k_mono, 0, length, samples.data());
    trace.input_copy_ns = elapsed_ns(copy_start, SteadyClock::now());

    std::lock_guard<std::mutex> lock(engine->lock);
    apply_force_language_override(engine, force_language);

    const auto native_start = SteadyClock::now();
    char* text = streaming
        ? qwen_transcribe_stream(engine->ctx, samples.data(), static_cast<int>(samples.size()))
        : qwen_transcribe_audio(engine->ctx, samples.data(), static_cast<int>(samples.size()));
    trace.native_inference_ns = elapsed_ns(native_start, SteadyClock::now());

    restore_force_language_override(engine, force_language);

    if (text == nullptr) {
        set_last_error(streaming
            ? "qwen_transcribe_stream returned null."
            : "qwen_transcribe_audio returned null.");
        trace.total_ns = elapsed_ns(total_start, SteadyClock::now());
        return trace;
    }

    trace.text.assign(text);
    free(text);
    trace.total_ns = elapsed_ns(total_start, SteadyClock::now());
    return trace;
}

jobject to_trace_object(JNIEnv* env, const NativeTrace& trace) {
    jclass trace_class = env->FindClass("com/example/cantonesevoiceinput/inference/NativeTranscriptionTrace");
    if (trace_class == nullptr) {
        return nullptr;
    }

    jmethodID ctor = env->GetMethodID(trace_class, "<init>", "(Ljava/lang/String;IJJJ)V");
    if (ctor == nullptr) {
        return nullptr;
    }

    jstring text = to_jstring(env, trace.text);
    jobject out = env->NewObject(
        trace_class,
        ctor,
        text,
        static_cast<jint>(trace.sample_count),
        static_cast<jlong>(trace.input_copy_ns),
        static_cast<jlong>(trace.native_inference_ns),
        static_cast<jlong>(trace.total_ns)
    );
    env->DeleteLocalRef(text);
    env->DeleteLocalRef(trace_class);
    return out;
}
#endif
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeCreate(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_dir,
    jstring language,
    jint threads
) {
#if HAVE_QWEN_ASR
    const char* model_dir_chars = env->GetStringUTFChars(model_dir, nullptr);
    const char* language_chars = env->GetStringUTFChars(language, nullptr);

    std::string model_dir_str = model_dir_chars ? model_dir_chars : "";
    std::string language_str = language_chars ? language_chars : "Cantonese";

    if (model_dir_chars != nullptr) {
        env->ReleaseStringUTFChars(model_dir, model_dir_chars);
    }
    if (language_chars != nullptr) {
        env->ReleaseStringUTFChars(language, language_chars);
    }

    int thread_count = static_cast<int>(threads);
    if (thread_count <= 0) {
        thread_count = qwen_get_num_cpus();
    }
    qwen_set_threads(thread_count);

    qwen_ctx_t* ctx = qwen_load(model_dir_str.c_str());
    if (ctx == nullptr) {
        set_last_error("qwen_load failed for: " + model_dir_str);
        return 0;
    }

    auto* engine = new NativeEngine();
    engine->ctx = ctx;
    engine->forced_language = normalize_language_for_qwen(language_str);

    if (!engine->forced_language.empty() &&
        qwen_set_force_language(ctx, engine->forced_language.c_str()) != 0) {
        set_last_error("Unsupported forced language: " + engine->forced_language);
        qwen_free(ctx);
        delete engine;
        return 0;
    }

    return reinterpret_cast<jlong>(engine);
#else
    (void)env;
    (void)model_dir;
    (void)language;
    (void)threads;
    set_last_error("qwen-asr native sources are missing. Vendor sources into app/src/main/cpp/third_party/qwen-asr.");
    return 0;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeConfigure(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jfloat segment_sec,
    jfloat search_sec,
    jboolean past_text_conditioning
) {
#if HAVE_QWEN_ASR
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> lock(engine->lock);
    if (segment_sec >= 0.0f) {
        engine->ctx->segment_sec = segment_sec;
    }
    if (search_sec >= 0.0f) {
        engine->ctx->search_sec = search_sec;
    }
    engine->ctx->past_text_conditioning = (past_text_conditioning == JNI_TRUE) ? 1 : 0;
#else
    (void)handle;
    (void)segment_sec;
    (void)search_sec;
    (void)past_text_conditioning;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeConfigureStreaming(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jfloat chunk_sec,
    jint rollback_tokens,
    jint unfixed_chunks,
    jint max_new_tokens
) {
#if HAVE_QWEN_ASR
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> lock(engine->lock);
    if (chunk_sec > 0.0f) {
        engine->ctx->stream_chunk_sec = chunk_sec;
    }
    if (rollback_tokens >= 0) {
        engine->ctx->stream_rollback = static_cast<int>(rollback_tokens);
    }
    if (unfixed_chunks >= 0) {
        engine->ctx->stream_unfixed_chunks = static_cast<int>(unfixed_chunks);
    }
    if (max_new_tokens > 0) {
        engine->ctx->stream_max_new_tokens = static_cast<int>(max_new_tokens);
    }
#else
    (void)handle;
    (void)chunk_sec;
    (void)rollback_tokens;
    (void)unfixed_chunks;
    (void)max_new_tokens;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeDestroy(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
#if HAVE_QWEN_ASR
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr) {
        return;
    }

    if (engine->ctx != nullptr) {
        qwen_free(engine->ctx);
        engine->ctx = nullptr;
    }
    delete engine;
#else
    (void)handle;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeTranscribe(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray pcm16k_mono,
    jboolean force_language
) {
#if HAVE_QWEN_ASR
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return to_jstring(env, "");
    }

    NativeTrace trace = run_transcription(env, engine, pcm16k_mono, force_language, false);
    return to_jstring(env, trace.text);
#else
    (void)handle;
    (void)pcm16k_mono;
    (void)force_language;
    return to_jstring(env, "");
#endif
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeTranscribeProfiled(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray pcm16k_mono,
    jboolean force_language
) {
#if HAVE_QWEN_ASR
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return nullptr;
    }

    NativeTrace trace = run_transcription(env, engine, pcm16k_mono, force_language, false);
    return to_trace_object(env, trace);
#else
    (void)env;
    (void)handle;
    (void)pcm16k_mono;
    (void)force_language;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeTranscribeStream(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray pcm16k_mono,
    jboolean force_language
) {
#if HAVE_QWEN_ASR
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return to_jstring(env, "");
    }

    NativeTrace trace = run_transcription(env, engine, pcm16k_mono, force_language, true);
    return to_jstring(env, trace.text);
#else
    (void)handle;
    (void)pcm16k_mono;
    (void)force_language;
    return to_jstring(env, "");
#endif
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeTranscribeStreamProfiled(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray pcm16k_mono,
    jboolean force_language
) {
#if HAVE_QWEN_ASR
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return nullptr;
    }

    NativeTrace trace = run_transcription(env, engine, pcm16k_mono, force_language, true);
    return to_trace_object(env, trace);
#else
    (void)env;
    (void)handle;
    (void)pcm16k_mono;
    (void)force_language;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeGetGlobalLastError(
    JNIEnv* env,
    jobject /* thiz */
) {
    return to_jstring(env, get_last_error());
}
