#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
std::mutex engine_mutex;
std::once_flag backend_init_once;
llama_model *model = nullptr;
llama_context *context = nullptr;
std::atomic<bool> cancelled{false};

bool should_abort(void *) {
    return cancelled.load();
}

void append_utf8(std::string &out, uint32_t codepoint) {
    if (codepoint <= 0x7f) {
        out.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
        out.push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    }
}

std::string from_java(JNIEnv *env, jstring value) {
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    std::string result;
    result.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xd800 && codepoint <= 0xdbff && i + 1 < length &&
            chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
            codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (chars[++i] - 0xdc00);
        }
        append_utf8(result, codepoint);
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring to_java(JNIEnv *env, const std::string &value) {
    std::vector<jchar> result;
    result.reserve(value.size());
    for (size_t i = 0; i < value.size();) {
        const auto first = static_cast<uint8_t>(value[i++]);
        uint32_t codepoint = 0xfffd;
        size_t trailing = 0;
        if (first < 0x80) {
            codepoint = first;
        } else if ((first & 0xe0) == 0xc0) {
            codepoint = first & 0x1f;
            trailing = 1;
        } else if ((first & 0xf0) == 0xe0) {
            codepoint = first & 0x0f;
            trailing = 2;
        } else if ((first & 0xf8) == 0xf0) {
            codepoint = first & 0x07;
            trailing = 3;
        }
        bool valid = i + trailing <= value.size();
        for (size_t j = 0; valid && j < trailing; ++j) {
            const auto next = static_cast<uint8_t>(value[i + j]);
            if ((next & 0xc0) != 0x80) {
                valid = false;
            } else {
                codepoint = (codepoint << 6) | (next & 0x3f);
            }
        }
        if (valid) {
            i += trailing;
        } else {
            codepoint = 0xfffd;
        }
        if (codepoint <= 0xffff) {
            result.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            result.push_back(static_cast<jchar>(0xd800 | (codepoint >> 10)));
            result.push_back(static_cast<jchar>(0xdc00 | (codepoint & 0x3ff)));
        }
    }
    return env->NewString(result.data(), static_cast<jsize>(result.size()));
}

void throw_java(JNIEnv *env, const char *message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) {
        env->ThrowNew(exception, message);
    }
}

void free_engine() {
    if (context != nullptr) {
        llama_free(context);
        context = nullptr;
    }
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_thecommonai_commonmobile_LlamaEngine_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(COMMON_LLAMA_BUILD);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thecommonai_commonmobile_LlamaEngine_nativeLoad(
    JNIEnv *env,
    jobject,
    jstring path,
    jboolean require_thinking_switch
) {
    const std::string model_path = from_java(env, path);
    std::lock_guard<std::mutex> guard(engine_mutex);
    free_engine();
    std::call_once(backend_init_once, [] { llama_backend_init(); });
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        throw_java(env, "The local runtime could not load this GGUF model.");
        return JNI_FALSE;
    }
    const char *chat_template = llama_model_chat_template(model, nullptr);
    if (require_thinking_switch == JNI_TRUE &&
        (chat_template == nullptr || std::string(chat_template).find("enable_thinking") == std::string::npos)) {
        free_engine();
        throw_java(env, "This Bonsai 2 model does not expose the required non-thinking chat-template setting.");
        return JNI_FALSE;
    }

    auto context_params = llama_context_default_params();
    context_params.n_ctx = 4096;
    context_params.n_batch = 512;
    context_params.n_threads = std::max(1, std::min(4, static_cast<int>(std::thread::hardware_concurrency())));
    context_params.n_threads_batch = context_params.n_threads;
    context_params.abort_callback = should_abort;
    context = llama_init_from_model(model, context_params);
    if (context == nullptr) {
        free_engine();
        throw_java(env, "The local runtime could not allocate an inference context.");
        return JNI_FALSE;
    }

    cancelled.store(false);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_thecommonai_commonmobile_LlamaEngine_nativeResetCancellation(JNIEnv *, jobject) {
    cancelled.store(false);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_thecommonai_commonmobile_LlamaEngine_nativeGenerate(
    JNIEnv *env,
    jobject,
    jobjectArray roles,
    jobjectArray contents,
    jint max_tokens,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat presence_penalty,
    jboolean disable_thinking
) {
    std::lock_guard<std::mutex> guard(engine_mutex);
    if (model == nullptr || context == nullptr) {
        throw_java(env, "No local model is loaded.");
        return nullptr;
    }
    const jsize count = env->GetArrayLength(roles);
    if (count == 0 || count != env->GetArrayLength(contents) || count > 128) {
        throw_java(env, "The inference request contains an invalid message list.");
        return nullptr;
    }

    std::vector<std::string> role_values;
    std::vector<std::string> content_values;
    std::vector<llama_chat_message> messages;
    role_values.reserve(count);
    content_values.reserve(count);
    messages.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        if (role == nullptr || content == nullptr) {
            throw_java(env, "The inference request contains an empty message.");
            return nullptr;
        }
        role_values.push_back(from_java(env, role));
        content_values.push_back(from_java(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    for (size_t i = 0; i < role_values.size(); ++i) {
        messages.push_back({role_values[i].c_str(), content_values[i].c_str()});
    }

    const char *chat_template = llama_model_chat_template(model, nullptr);
    std::string template_override;
    if (disable_thinking == JNI_TRUE) {
        if (chat_template == nullptr || std::string(chat_template).find("enable_thinking") == std::string::npos) {
            throw_java(env, "This Bonsai 2 model template cannot safely disable thinking mode.");
            return nullptr;
        }
        template_override = chat_template;
        size_t position = 0;
        while ((position = template_override.find("enable_thinking", position)) != std::string::npos) {
            template_override.replace(position, sizeof("enable_thinking") - 1, "false");
            position += sizeof("false") - 1;
        }
        chat_template = template_override.c_str();
    }
    llama_memory_clear(llama_get_memory(context), true);
    std::vector<char> prompt(1 << 20);
    const int32_t prompt_size = llama_chat_apply_template(
        chat_template, messages.data(), messages.size(), true, prompt.data(), static_cast<int32_t>(prompt.size())
    );
    if (prompt_size <= 0 || static_cast<size_t>(prompt_size) >= prompt.size()) {
        throw_java(env, "The local model's chat template is unavailable or exceeds the prompt limit.");
        return nullptr;
    }
    const auto *vocab = llama_model_get_vocab(model);
    std::vector<llama_token> tokens(static_cast<size_t>(prompt_size) + 8);
    int32_t token_count = llama_tokenize(
        vocab, prompt.data(), prompt_size, tokens.data(), static_cast<int32_t>(tokens.size()), true, true
    );
    const int bounded_max_tokens = std::clamp(static_cast<int>(max_tokens), 1, 1024);
    if (token_count <= 0 || token_count + bounded_max_tokens > 4096) {
        throw_java(env, "The prompt exceeds this device's local context limit.");
        return nullptr;
    }

    auto batch = llama_batch_get_one(tokens.data(), token_count);
    if (llama_decode(context, batch) != 0) {
        throw_java(env, "The local runtime could not evaluate the prompt.");
        return nullptr;
    }

    auto sampler_params = llama_sampler_chain_default_params();
    auto *sampler = llama_sampler_chain_init(sampler_params);
    if (sampler == nullptr) {
        throw_java(env, "The local runtime could not initialize token sampling.");
        return nullptr;
    }
    if (presence_penalty != 0.0f) {
        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab), 64, 1.0f, 0.0f, std::clamp(presence_penalty, -2.0f, 2.0f)
            )
        );
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(std::clamp(static_cast<int>(top_k), 1, 100)));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(std::clamp(top_p, 0.01f, 1.0f), 1));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string output;
    std::vector<char> piece(32 * 1024);
    const auto eos = llama_vocab_eos(vocab);
    const auto eot = llama_vocab_eot(vocab);
    for (int i = 0; i < bounded_max_tokens; ++i) {
        if (cancelled.load()) break;
        const llama_token token = llama_sampler_sample(sampler, context, -1);
        if (token == eos || token == eot) break;
        const int32_t size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (size < 0 || output.size() + static_cast<size_t>(size) > (1 << 20)) {
            llama_sampler_free(sampler);
            throw_java(env, "The local response exceeded the supported size.");
            return nullptr;
        }
        output.append(piece.data(), static_cast<size_t>(size));
        if (cancelled.load()) break;
        llama_token next = token;
        batch = llama_batch_get_one(&next, 1);
        const int32_t decode_result = llama_decode(context, batch);
        if (decode_result != 0) {
            if (cancelled.load()) break;
            llama_sampler_free(sampler);
            throw_java(env, "The local runtime could not continue generation.");
            return nullptr;
        }
    }
    llama_sampler_free(sampler);
    return to_java(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_thecommonai_commonmobile_LlamaEngine_nativeCancel(JNIEnv *, jobject) {
    cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_thecommonai_commonmobile_LlamaEngine_nativeFree(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(engine_mutex);
    free_engine();
}
