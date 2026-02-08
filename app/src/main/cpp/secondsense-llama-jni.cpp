#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <memory>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>

#include "common.h"
#include "llama.h"
#include "sampling.h"

#define TAG "secondsense-llama-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct NativeSession {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    llama_batch batch = {};
    common_sampler * sampler = nullptr;
    int n_ctx = 0;
    int n_batch = 0;
};

std::mutex g_backend_mutex;
int g_backend_ref_count = 0;

void log_callback(ggml_log_level level, const char * text, void * /* user_data */) {
    if (text == nullptr) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
            break;
        case GGML_LOG_LEVEL_WARN:
            __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
            break;
        case GGML_LOG_LEVEL_INFO:
            __android_log_print(ANDROID_LOG_INFO, TAG, "%s", text);
            break;
        default:
            __android_log_print(ANDROID_LOG_DEBUG, TAG, "%s", text);
            break;
    }
}

void throw_illegal_state(JNIEnv * env, const std::string & message) {
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    if (ex != nullptr) {
        env->ThrowNew(ex, message.c_str());
    }
}

void ensure_backend_initialized() {
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    if (g_backend_ref_count == 0) {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
        LOGI("llama backend initialized: %s", llama_print_system_info());
    }
    g_backend_ref_count += 1;
}

void release_backend() {
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    if (g_backend_ref_count <= 0) return;
    g_backend_ref_count -= 1;
    if (g_backend_ref_count == 0) {
        llama_backend_free();
        LOGI("llama backend released");
    }
}

void free_session(NativeSession * session) {
    if (session == nullptr) return;

    if (session->sampler != nullptr) {
        common_sampler_free(session->sampler);
        session->sampler = nullptr;
    }
    if (session->batch.token != nullptr || session->batch.embd != nullptr) {
        llama_batch_free(session->batch);
        session->batch = {};
    }
    if (session->context != nullptr) {
        llama_free(session->context);
        session->context = nullptr;
    }
    if (session->model != nullptr) {
        llama_model_free(session->model);
        session->model = nullptr;
    }
}

bool recreate_sampler(NativeSession * session, float temperature, float top_p) {
    if (session == nullptr || session->model == nullptr) return false;

    if (session->sampler != nullptr) {
        common_sampler_free(session->sampler);
        session->sampler = nullptr;
    }

    common_params_sampling params;
    params.temp = temperature;
    params.top_p = top_p;
    params.penalty_repeat = 1.0f;
    params.penalty_freq = 0.0f;
    params.penalty_present = 0.0f;

    session->sampler = common_sampler_init(session->model, params);
    return session->sampler != nullptr;
}

bool decode_tokens_in_batches(
    NativeSession * session,
    const llama_tokens & tokens,
    const int start_pos,
    const bool compute_last_logit
) {
    if (session == nullptr) return false;
    if (tokens.empty()) return true;

    for (int i = 0; i < static_cast<int>(tokens.size()); i += session->n_batch) {
        const int cur_batch_size = std::min(static_cast<int>(tokens.size()) - i, session->n_batch);
        common_batch_clear(session->batch);

        for (int j = 0; j < cur_batch_size; ++j) {
            const bool want_logit = compute_last_logit && (i + j == static_cast<int>(tokens.size()) - 1);
            common_batch_add(session->batch, tokens[i + j], start_pos + i + j, {0}, want_logit);
        }

        const int decode_result = llama_decode(session->context, session->batch);
        if (decode_result != 0) {
            LOGE("llama_decode failed: %d", decode_result);
            return false;
        }
    }

    return true;
}

bool is_valid_utf8(const char * text) {
    if (!text) return true;

    const auto * bytes = reinterpret_cast<const unsigned char *>(text);
    while (*bytes != 0x00) {
        int num = 0;
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }

    return true;
}

bool has_complete_json_object(const std::string & text) {
    int depth = 0;
    int start = -1;
    bool in_string = false;
    bool escaping = false;

    for (int i = 0; i < static_cast<int>(text.size()); ++i) {
        const char ch = text[i];

        if (in_string) {
            if (escaping) {
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '"') {
                in_string = false;
            }
            continue;
        }

        if (ch == '"') {
            in_string = true;
            continue;
        }

        if (ch == '{') {
            if (depth == 0) {
                start = i;
            }
            depth += 1;
            continue;
        }

        if (ch == '}' && depth > 0) {
            depth -= 1;
            if (depth == 0 && start >= 0) {
                return true;
            }
        }
    }

    return false;
}

} // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_com_secondsense_llm_LlamaNativeBridge_nativeLoadModel(
    JNIEnv * env,
    jobject /* this */,
    jstring modelPath,
    jint contextSize,
    jint threads
) {
    if (modelPath == nullptr) {
        throw_illegal_state(env, "Model path cannot be null.");
        return 0L;
    }

    const char * model_path_chars = env->GetStringUTFChars(modelPath, nullptr);
    const std::string path = model_path_chars != nullptr ? model_path_chars : "";
    env->ReleaseStringUTFChars(modelPath, model_path_chars);

    if (path.empty()) {
        throw_illegal_state(env, "Model path cannot be empty.");
        return 0L;
    }

    auto session = std::make_unique<NativeSession>();
    ensure_backend_initialized();

    llama_model_params model_params = llama_model_default_params();
    session->model = llama_model_load_from_file(path.c_str(), model_params);
    if (session->model == nullptr) {
        free_session(session.get());
        release_backend();
        throw_illegal_state(env, "Failed to load model from file.");
        return 0L;
    }

    session->n_ctx = std::max(512, static_cast<int>(contextSize));
    session->n_batch = std::min(session->n_ctx, 512);
    const int n_threads = std::max(1, static_cast<int>(threads));

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = session->n_ctx;
    context_params.n_batch = session->n_batch;
    context_params.n_ubatch = session->n_batch;
    context_params.n_threads = n_threads;
    context_params.n_threads_batch = n_threads;

    session->context = llama_init_from_model(session->model, context_params);
    if (session->context == nullptr) {
        free_session(session.get());
        release_backend();
        throw_illegal_state(env, "Failed to create llama context.");
        return 0L;
    }

    session->batch = llama_batch_init(session->n_batch, 0, 1);
    if (session->batch.token == nullptr) {
        free_session(session.get());
        release_backend();
        throw_illegal_state(env, "Failed to allocate llama batch.");
        return 0L;
    }

    if (!recreate_sampler(session.get(), 0.2f, 0.9f)) {
        free_session(session.get());
        release_backend();
        throw_illegal_state(env, "Failed to initialize sampler.");
        return 0L;
    }

    LOGI("Model loaded: %s (ctx=%d, batch=%d, threads=%d)",
         path.c_str(),
         session->n_ctx,
         session->n_batch,
         n_threads);
    return reinterpret_cast<jlong>(session.release());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_secondsense_llm_LlamaNativeBridge_nativeUnloadModel(
    JNIEnv * /* env */,
    jobject /* this */,
    jlong handle
) {
    if (handle == 0L) return;

    auto * session = reinterpret_cast<NativeSession *>(handle);
    free_session(session);
    delete session;
    release_backend();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_secondsense_llm_LlamaNativeBridge_nativeGenerate(
    JNIEnv * env,
    jobject /* this */,
    jlong handle,
    jstring prompt,
    jint maxTokens,
    jfloat temperature,
    jfloat topP
) {
    if (handle == 0L) {
        throw_illegal_state(env, "Invalid model handle.");
        return nullptr;
    }
    if (prompt == nullptr) {
        throw_illegal_state(env, "Prompt cannot be null.");
        return nullptr;
    }

    auto * session = reinterpret_cast<NativeSession *>(handle);
    if (session->model == nullptr || session->context == nullptr) {
        throw_illegal_state(env, "Model session is not initialized.");
        return nullptr;
    }

    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    const std::string prompt_text = prompt_chars != nullptr ? prompt_chars : "";
    env->ReleaseStringUTFChars(prompt, prompt_chars);

    const int requested_predict = static_cast<int>(maxTokens);
    const float clamped_temp = std::max(0.0f, static_cast<float>(temperature));
    const float clamped_top_p = std::clamp(static_cast<float>(topP), 0.0f, 1.0f);
    LOGI(
        "nativeGenerate start prompt_chars=%zu max_tokens=%d temp=%.3f top_p=%.3f",
        prompt_text.size(),
        requested_predict,
        clamped_temp,
        clamped_top_p
    );

    llama_memory_clear(llama_get_memory(session->context), false);
    if (!recreate_sampler(session, clamped_temp, clamped_top_p)) {
        throw_illegal_state(env, "Failed to initialize sampler for generation.");
        return nullptr;
    }

    llama_tokens prompt_tokens = common_tokenize(session->context, prompt_text, true, true);
    if (prompt_tokens.empty()) {
        throw_illegal_state(env, "Prompt tokenization produced no tokens.");
        return nullptr;
    }

    int n_predict = requested_predict;
    if (n_predict <= 0) {
        // Auto mode: leave room for decoding and stop conditions.
        n_predict = std::max(1, session->n_ctx - static_cast<int>(prompt_tokens.size()) - 2);
    }
    if (static_cast<int>(prompt_tokens.size()) >= session->n_ctx - 4) {
        throw_illegal_state(env, "Prompt is too long for current context size.");
        return nullptr;
    }

    if (!decode_tokens_in_batches(session, prompt_tokens, 0, true)) {
        throw_illegal_state(env, "Failed while decoding prompt.");
        return nullptr;
    }

    std::string output;
    output.reserve(static_cast<size_t>(n_predict) * 4);
    std::string pending_utf8;
    int generated_tokens = 0;
    int current_position = static_cast<int>(prompt_tokens.size());
    const llama_vocab * vocab = llama_model_get_vocab(session->model);

    for (int i = 0; i < n_predict; ++i) {
        if (current_position >= session->n_ctx - 2) break;

        const llama_token token = common_sampler_sample(session->sampler, session->context, -1);
        common_sampler_accept(session->sampler, token, true);
        generated_tokens += 1;

        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        pending_utf8 += common_token_to_piece(session->context, token);
        if (is_valid_utf8(pending_utf8.c_str())) {
            output += pending_utf8;
            pending_utf8.clear();

            if (has_complete_json_object(output)) {
                LOGI("nativeGenerate stopping early after first complete JSON object.");
                break;
            }
        }

        common_batch_clear(session->batch);
        common_batch_add(session->batch, token, current_position, {0}, true);
        const int decode_result = llama_decode(session->context, session->batch);
        if (decode_result != 0) {
            throw_illegal_state(env, "Failed while decoding generated token.");
            return nullptr;
        }
        current_position += 1;
    }

    LOGI(
        "nativeGenerate done generated_tokens=%d output_chars=%zu",
        generated_tokens,
        output.size()
    );
    return env->NewStringUTF(output.c_str());
}
