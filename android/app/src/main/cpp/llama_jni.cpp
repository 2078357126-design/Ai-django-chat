#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "aicpp"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *smpl = nullptr;
    std::atomic<bool> stop{false};
    std::mutex mutex;
};

void throwJava(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int needed = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                nullptr, 0, true, true);
    if (needed <= 0) {
        return {};
    }
    std::vector<llama_token> tokens(needed);
    int actual = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                               tokens.data(), needed, true, true);
    if (actual < 0) {
        return {};
    }
    tokens.resize(actual);
    return tokens;
}

// 判断一段 UTF-8 是否以完整字符结尾，避免把多字节汉字/emoji 拆断
bool endsCompleteUtf8(const std::string &text) {
    if (text.empty()) {
        return true;
    }
    size_t index = text.size();
    while (index > 0 && (static_cast<unsigned char>(text[index - 1]) & 0xC0) == 0x80) {
        --index;
    }
    if (index == 0) {
        return true;
    }
    unsigned char lead = static_cast<unsigned char>(text[index - 1]);
    int needed = 1;
    if (lead >= 0xF0) {
        needed = 4;
    } else if (lead >= 0xE0) {
        needed = 3;
    } else if (lead >= 0xC0) {
        needed = 2;
    }
    return static_cast<int>(text.size() - (index - 1)) >= needed;
}

void emitToken(JNIEnv *env, jobject callback, jmethodID method, const std::string &text) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(text.size()));
    if (array == nullptr) {
        return;
    }
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(text.size()),
                            reinterpret_cast<const jbyte *>(text.data()));
    env->CallVoidMethod(callback, method, array);
    env->DeleteLocalRef(array);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_aichat_app_LlamaBridge_nativeLoad(JNIEnv *env, jclass, jstring model_path,
                                           jstring mmproj_path, jint context_size,
                                           jint threads) {
    if (model_path == nullptr) {
        return 0;
    }
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) {
        return 0;
    }
    std::string modelPath(path);
    env->ReleaseStringUTFChars(model_path, path);
    // mmproj 预留给后续的看图功能，这一步先只加载文本模型
    (void) mmproj_path;

    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model *model = llama_model_load_from_file(modelPath.c_str(), modelParams);
    if (model == nullptr) {
        LOGE("load model failed: %s", modelPath.c_str());
        return 0;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = static_cast<uint32_t>(std::max(256, static_cast<int>(context_size)));
    contextParams.n_batch = 512;
    contextParams.n_threads = threads;
    contextParams.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, contextParams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }

    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    Engine *engine = new Engine();
    engine->model = model;
    engine->ctx = ctx;
    engine->smpl = smpl;
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aichat_app_LlamaBridge_nativeChat(JNIEnv *env, jclass, jlong handle, jstring prompt,
                                           jobject callback, jint max_tokens) {
    Engine *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr || prompt == nullptr || callback == nullptr) {
        return;
    }
    const char *text = env->GetStringUTFChars(prompt, nullptr);
    if (text == nullptr) {
        return;
    }
    std::string promptText(text);
    env->ReleaseStringUTFChars(prompt, text);

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(callbackClass, "onToken", "([B)V");
    if (onToken == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> lock(engine->mutex);
    engine->stop = false;
    // 每次对话从干净的 KV 缓存开始，避免多轮之后把上下文撑爆
    llama_memory_clear(llama_get_memory(engine->ctx), true);

    const llama_vocab *vocab = llama_model_get_vocab(engine->model);
    std::vector<llama_token> tokens = tokenize(vocab, promptText);
    if (tokens.empty()) {
        throwJava(env, "提示词编码失败");
        return;
    }

    int nCtx = static_cast<int>(llama_n_ctx(engine->ctx));
    int maxPrompt = std::max(64, nCtx - static_cast<int>(max_tokens) - 16);
    if (static_cast<int>(tokens.size()) > maxPrompt) {
        tokens.erase(tokens.begin(), tokens.end() - maxPrompt);
    }

    int nBatch = static_cast<int>(llama_n_batch(engine->ctx));
    for (size_t i = 0; i < tokens.size(); i += static_cast<size_t>(nBatch)) {
        int count = std::min(nBatch, static_cast<int>(tokens.size() - i));
        llama_batch batch = llama_batch_get_one(tokens.data() + i, count);
        if (llama_decode(engine->ctx, batch) != 0) {
            throwJava(env, "模型推理失败，请重试");
            return;
        }
    }

    std::string pending;
    int generated = 0;
    while (generated < static_cast<int>(max_tokens) && !engine->stop.load()) {
        llama_token id = llama_sampler_sample(engine->smpl, engine->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }
        char buffer[256];
        int length = llama_token_to_piece(vocab, id, buffer, sizeof(buffer), 0, false);
        if (length > 0) {
            pending.append(buffer, length);
            if (endsCompleteUtf8(pending)) {
                emitToken(env, callback, onToken, pending);
                pending.clear();
            }
        }
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(engine->ctx, batch) != 0) {
            break;
        }
        ++generated;
    }
    if (!pending.empty()) {
        emitToken(env, callback, onToken, pending);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aichat_app_LlamaBridge_nativeStop(JNIEnv *, jclass, jlong handle) {
    Engine *engine = reinterpret_cast<Engine *>(handle);
    if (engine != nullptr) {
        engine->stop = true;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aichat_app_LlamaBridge_nativeFree(JNIEnv *, jclass, jlong handle) {
    Engine *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) {
        return;
    }
    engine->stop = true;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        if (engine->smpl != nullptr) {
            llama_sampler_free(engine->smpl);
            engine->smpl = nullptr;
        }
        if (engine->ctx != nullptr) {
            llama_free(engine->ctx);
            engine->ctx = nullptr;
        }
        if (engine->model != nullptr) {
            llama_model_free(engine->model);
            engine->model = nullptr;
        }
    }
    delete engine;
}
