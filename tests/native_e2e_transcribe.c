#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "qwen_asr.h"
#include "qwen_asr_audio.h"
#include "qwen_asr_kernels.h"

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <model_dir> <input_wav> [language] [mode]\n", argv[0]);
        return 2;
    }

    const char *model_dir = argv[1];
    const char *input_wav = argv[2];
    const char *language = (argc >= 4) ? argv[3] : "Cantonese";
    const char *mode = (argc >= 5) ? argv[4] : "normal";

    qwen_set_threads(qwen_get_num_cpus());

    qwen_ctx_t *ctx = qwen_load(model_dir);
    if (!ctx) {
        fprintf(stderr, "e2e: failed to load model from %s\n", model_dir);
        return 3;
    }

    if (language[0] != '\0') {
        if (qwen_set_force_language(ctx, language) != 0) {
            fprintf(stderr, "e2e: unsupported language: %s\n", language);
            qwen_free(ctx);
            return 4;
        }
    }

    char *text = NULL;
    if (strcmp(mode, "stream") == 0) {
        int n_samples = 0;
        float *samples = qwen_load_wav(input_wav, &n_samples);
        if (!samples || n_samples <= 0) {
            fprintf(stderr, "e2e: failed to load wav samples for stream mode: %s\n", input_wav);
            qwen_free(ctx);
            free(samples);
            return 5;
        }
        text = qwen_transcribe_stream(ctx, samples, n_samples);
        free(samples);
    } else {
        text = qwen_transcribe(ctx, input_wav);
    }

    if (!text) {
        fprintf(stderr, "e2e: transcription failed for %s (mode=%s)\n", input_wav, mode);
        qwen_free(ctx);
        return 6;
    }

    printf("%s\n", text);
    free(text);
    qwen_free(ctx);
    return 0;
}
