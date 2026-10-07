// JNI bindings of com.pilot.voiceassistant.eal.llama.LlamaNative.

#include <jni.h>

#include <android/log.h>

#include <string>

#include "llama.h"
#include "llm_engine.h"

namespace {

const char *const kLogTag = "llama.cpp";

// llama.cpp is chatty at info level, so only its warnings and errors reach logcat.
void log_to_logcat(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, kLogTag, text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, kLogTag, text);
    }
}

void throw_illegal_state(JNIEnv *env, const std::string &message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}

std::string to_string(JNIEnv *env, jbyteArray bytes) {
    jsize length = env->GetArrayLength(bytes);
    std::string result((size_t) length, '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(&result[0]));
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_pilot_voiceassistant_eal_llama_LlamaNative_load(JNIEnv *env, jclass, jstring model_path,
                                                         jint context_tokens, jint threads) {
    llama_log_set(log_to_logcat, nullptr);
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    auto *engine = new LlmEngine();
    std::string error;
    bool loaded = engine->load(path, context_tokens, threads, &error);
    env->ReleaseStringUTFChars(model_path, path);
    if (!loaded) {
        delete engine;
        throw_illegal_state(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jbyteArray JNICALL
Java_com_pilot_voiceassistant_eal_llama_LlamaNative_generate(JNIEnv *env, jclass, jlong handle,
                                                             jbyteArray preamble,
                                                             jbyteArray request, jint max_tokens,
                                                             jbyteArray stop) {
    auto *engine = reinterpret_cast<LlmEngine *>(handle);
    std::string output;
    std::string error;
    if (!engine->generate(to_string(env, preamble), to_string(env, request), max_tokens,
                          to_string(env, stop), &output, &error)) {
        throw_illegal_state(env, error);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray((jsize) output.size());
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, (jsize) output.size(),
                                reinterpret_cast<const jbyte *>(output.data()));
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_pilot_voiceassistant_eal_llama_LlamaNative_cancel(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<LlmEngine *>(handle)->cancel();
}

JNIEXPORT void JNICALL
Java_com_pilot_voiceassistant_eal_llama_LlamaNative_release(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<LlmEngine *>(handle);
}

}  // extern "C"
