#include <jni.h>

#include <chrono>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>

extern "C" {
#include "qwen_asr.h"
#include "qwen_asr_kernels.h"
}

namespace {
constexpr const char* kTag = "QwenAsrJni";
std::mutex g_error_mutex;
std::string g_last_error;

void clear_last_error() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error.clear();
}

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

struct TokenCallbackState {
    JavaVM* jvm = nullptr;
    jobject listener = nullptr;
    jmethodID on_token_piece = nullptr;
};

struct NativeLiveSession {
    NativeEngine* engine = nullptr;
    qwen_live_audio_t live = {};
    TokenCallbackState callback;
    jboolean force_language = JNI_TRUE;
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

bool prepare_token_callback(JNIEnv* env, jobject listener, TokenCallbackState* state) {
    if (listener == nullptr || state == nullptr) {
        return true;
    }

    clear_last_error();

    if (env->GetJavaVM(&state->jvm) != JNI_OK) {
        set_last_error("Failed to access Java VM for streaming callback.");
        return false;
    }

    jobject global_listener = env->NewGlobalRef(listener);
    if (global_listener == nullptr) {
        set_last_error("Failed to create global reference for streaming callback.");
        return false;
    }

    jclass listener_class = env->GetObjectClass(listener);
    if (listener_class == nullptr) {
        env->DeleteGlobalRef(global_listener);
        set_last_error("Failed to resolve streaming callback class.");
        return false;
    }

    jmethodID on_token_piece = env->GetMethodID(
        listener_class,
        "onTokenPiece",
        "(Ljava/lang/String;)V"
    );
    env->DeleteLocalRef(listener_class);

    if (on_token_piece == nullptr) {
        env->DeleteGlobalRef(global_listener);
        set_last_error("Failed to resolve StreamingTokenListener.onTokenPiece.");
        return false;
    }

    state->listener = global_listener;
    state->on_token_piece = on_token_piece;
    return true;
}

void release_token_callback(JNIEnv* env, TokenCallbackState* state) {
    if (state == nullptr || state->listener == nullptr) {
        return;
    }
    env->DeleteGlobalRef(state->listener);
    state->listener = nullptr;
    state->on_token_piece = nullptr;
    state->jvm = nullptr;
}

void forward_token_piece_to_java(const char* piece, void* userdata) {
    auto* state = reinterpret_cast<TokenCallbackState*>(userdata);
    if (state == nullptr || state->listener == nullptr || state->on_token_piece == nullptr || piece == nullptr) {
        return;
    }

    JNIEnv* env = nullptr;
    bool should_detach = false;
    const jint env_status = state->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (env_status == JNI_EDETACHED) {
        if (state->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            set_last_error("Failed to attach streaming callback thread to JVM.");
            return;
        }
        should_detach = true;
    } else if (env_status != JNI_OK) {
        set_last_error("Failed to acquire JNIEnv for streaming callback.");
        return;
    }

    jstring token_piece = to_jstring(env, piece);
    env->CallVoidMethod(state->listener, state->on_token_piece, token_piece);
    env->DeleteLocalRef(token_piece);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        set_last_error("Streaming callback threw an exception.");
    }

    if (should_detach) {
        state->jvm->DetachCurrentThread();
    }
}

bool initialize_live_audio(qwen_live_audio_t* live) {
    if (live == nullptr) {
        return false;
    }

    std::memset(live, 0, sizeof(*live));

    if (pthread_mutex_init(&live->mutex, nullptr) != 0) {
        set_last_error("Failed to initialize live audio mutex.");
        return false;
    }
    if (pthread_cond_init(&live->cond, nullptr) != 0) {
        pthread_mutex_destroy(&live->mutex);
        set_last_error("Failed to initialize live audio condition variable.");
        return false;
    }
    return true;
}

void destroy_live_audio(qwen_live_audio_t* live) {
    if (live == nullptr) {
        return;
    }

    free(live->samples);
    live->samples = nullptr;
    live->sample_offset = 0;
    live->n_samples = 0;
    live->capacity = 0;
    live->eof = 1;
    pthread_cond_destroy(&live->cond);
    pthread_mutex_destroy(&live->mutex);
}

bool append_live_audio(qwen_live_audio_t* live, const float* samples, int count) {
    if (live == nullptr || samples == nullptr || count <= 0) {
        return true;
    }

    pthread_mutex_lock(&live->mutex);

    const int64_t required = live->n_samples + static_cast<int64_t>(count);
    if (required > live->capacity) {
        int64_t new_capacity = live->capacity > 0 ? live->capacity : static_cast<int64_t>(count);
        while (new_capacity < required) {
            new_capacity *= 2;
        }

        float* resized = reinterpret_cast<float*>(
            realloc(live->samples, static_cast<size_t>(new_capacity) * sizeof(float))
        );
        if (resized == nullptr) {
            pthread_mutex_unlock(&live->mutex);
            set_last_error("Failed to grow live audio buffer.");
            return false;
        }

        live->samples = resized;
        live->capacity = new_capacity;
    }

    std::memcpy(
        live->samples + live->n_samples,
        samples,
        static_cast<size_t>(count) * sizeof(float)
    );
    live->n_samples += count;
    pthread_cond_broadcast(&live->cond);
    pthread_mutex_unlock(&live->mutex);
    return true;
}

void finish_live_audio(qwen_live_audio_t* live) {
    if (live == nullptr) {
        return;
    }

    pthread_mutex_lock(&live->mutex);
    live->eof = 1;
    pthread_cond_broadcast(&live->cond);
    pthread_mutex_unlock(&live->mutex);
}

NativeTrace run_transcription(
    JNIEnv* env,
    NativeEngine* engine,
    jfloatArray pcm16k_mono,
    jboolean force_language,
    bool streaming,
    jobject listener
) {
    NativeTrace trace;
    const auto total_start = SteadyClock::now();
    clear_last_error();

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

    TokenCallbackState callback_state;
    if (streaming) {
        if (!prepare_token_callback(env, listener, &callback_state)) {
            restore_force_language_override(engine, force_language);
            trace.total_ns = elapsed_ns(total_start, SteadyClock::now());
            return trace;
        }
        qwen_set_token_callback(
            engine->ctx,
            callback_state.listener != nullptr ? forward_token_piece_to_java : nullptr,
            callback_state.listener != nullptr ? &callback_state : nullptr
        );
    }

    const auto native_start = SteadyClock::now();
    char* text = streaming
        ? qwen_transcribe_stream(engine->ctx, samples.data(), static_cast<int>(samples.size()))
        : qwen_transcribe_audio(engine->ctx, samples.data(), static_cast<int>(samples.size()));
    trace.native_inference_ns = elapsed_ns(native_start, SteadyClock::now());

    if (streaming) {
        qwen_set_token_callback(engine->ctx, nullptr, nullptr);
        release_token_callback(env, &callback_state);
    }

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
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeCreate(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_dir,
    jstring language,
    jint threads
) {
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
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeDestroy(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr) {
        return;
    }

    if (engine->ctx != nullptr) {
        qwen_free(engine->ctx);
        engine->ctx = nullptr;
    }
    delete engine;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeTranscribe(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray pcm16k_mono,
    jboolean force_language
) {
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return to_jstring(env, "");
    }

    NativeTrace trace = run_transcription(env, engine, pcm16k_mono, force_language, false, nullptr);
    return to_jstring(env, trace.text);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeTranscribeProfiled(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray pcm16k_mono,
    jboolean force_language
) {
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return nullptr;
    }

    NativeTrace trace = run_transcription(env, engine, pcm16k_mono, force_language, false, nullptr);
    return to_trace_object(env, trace);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeTranscribeStream(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray pcm16k_mono,
    jboolean force_language,
    jobject listener
) {
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return to_jstring(env, "");
    }

    NativeTrace trace = run_transcription(env, engine, pcm16k_mono, force_language, true, listener);
    return to_jstring(env, trace.text);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeTranscribeStreamProfiled(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray pcm16k_mono,
    jboolean force_language
) {
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return nullptr;
    }

    NativeTrace trace = run_transcription(env, engine, pcm16k_mono, force_language, true, nullptr);
    return to_trace_object(env, trace);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeCreateLiveSession(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jboolean force_language,
    jobject listener
) {
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        set_last_error("Native engine is not available for live streaming.");
        return 0;
    }

    clear_last_error();

    auto* session = new NativeLiveSession();
    session->engine = engine;
    session->force_language = force_language;

    if (!initialize_live_audio(&session->live)) {
        delete session;
        return 0;
    }

    if (!prepare_token_callback(env, listener, &session->callback)) {
        destroy_live_audio(&session->live);
        delete session;
        return 0;
    }

    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeAppendLiveSessionAudio(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray pcm16k_mono
) {
    auto* session = reinterpret_cast<NativeLiveSession*>(handle);
    if (session == nullptr) {
        set_last_error("Live transcription session is null.");
        return JNI_FALSE;
    }

    const jsize length = env->GetArrayLength(pcm16k_mono);
    if (length <= 0) {
        return JNI_TRUE;
    }

    std::vector<float> samples(static_cast<size_t>(length));
    env->GetFloatArrayRegion(pcm16k_mono, 0, length, samples.data());
    return append_live_audio(&session->live, samples.data(), static_cast<int>(samples.size()))
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeFinishLiveSessionInput(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeLiveSession*>(handle);
    if (session == nullptr) {
        return;
    }

    finish_live_audio(&session->live);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeRunLiveSession(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeLiveSession*>(handle);
    if (session == nullptr || session->engine == nullptr || session->engine->ctx == nullptr) {
        return to_jstring(env, "");
    }

    clear_last_error();

    auto* engine = session->engine;
    std::lock_guard<std::mutex> lock(engine->lock);
    apply_force_language_override(engine, session->force_language);
    qwen_set_token_callback(
        engine->ctx,
        session->callback.listener != nullptr ? forward_token_piece_to_java : nullptr,
        session->callback.listener != nullptr ? &session->callback : nullptr
    );

    char* text = qwen_transcribe_stream_live(engine->ctx, &session->live);

    qwen_set_token_callback(engine->ctx, nullptr, nullptr);
    restore_force_language_override(engine, session->force_language);

    if (text == nullptr) {
        set_last_error("qwen_transcribe_stream_live returned null.");
        return to_jstring(env, "");
    }

    std::string out(text);
    free(text);
    return to_jstring(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeDestroyLiveSession(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeLiveSession*>(handle);
    if (session == nullptr) {
        return;
    }

    finish_live_audio(&session->live);
    release_token_callback(env, &session->callback);
    destroy_live_audio(&session->live);
    delete session;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_cantonesevoiceinput_inference_NativeQwenAsrEngine_nativeGetGlobalLastError(
    JNIEnv* env,
    jobject /* thiz */
) {
    return to_jstring(env, get_last_error());
}
