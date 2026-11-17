#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "GenieDialog.h"

namespace {

constexpr size_t kMaxOutputBytes = 64 * 1024;
constexpr auto kLogTag = "MALLO_LLM";
bool runtimeReleaseFailed = false;

struct Session {
    void* library;
    GenieDialog_Handle_t dialog;
    decltype(&GenieDialogConfig_createFromJson) createConfig;
    decltype(&GenieDialogConfig_free) freeConfig;
    decltype(&GenieDialog_create) createDialog;
    decltype(&GenieDialog_free) freeDialog;
    decltype(&GenieDialog_reset) resetDialog;
    decltype(&GenieDialog_query) query;
    decltype(&GenieDialog_signal) signal;
};

struct Response {
    char text[kMaxOutputBytes];
    size_t length;
    bool complete;
    bool overflow;
};

void fail(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}

void failStatus(JNIEnv* env, const char* operation, Genie_Status_t status) {
    char message[128];
    snprintf(message, sizeof(message), "%s failed (Genie status %d)", operation, static_cast<int>(status));
    fail(env, message);
}

bool loadFunctions(Session* session) {
    void* library = session->library;
    session->createConfig = reinterpret_cast<decltype(session->createConfig)>(dlsym(library, "GenieDialogConfig_createFromJson"));
    session->freeConfig = reinterpret_cast<decltype(session->freeConfig)>(dlsym(library, "GenieDialogConfig_free"));
    session->createDialog = reinterpret_cast<decltype(session->createDialog)>(dlsym(library, "GenieDialog_create"));
    session->freeDialog = reinterpret_cast<decltype(session->freeDialog)>(dlsym(library, "GenieDialog_free"));
    session->resetDialog = reinterpret_cast<decltype(session->resetDialog)>(dlsym(library, "GenieDialog_reset"));
    session->query = reinterpret_cast<decltype(session->query)>(dlsym(library, "GenieDialog_query"));
    session->signal = reinterpret_cast<decltype(session->signal)>(dlsym(library, "GenieDialog_signal"));
    return session->createConfig && session->freeConfig && session->createDialog &&
           session->freeDialog && session->resetDialog && session->query && session->signal;
}

bool release(Session* session) {
    if (session->dialog) {
        const auto status = session->freeDialog(session->dialog);
        if (status != GENIE_STATUS_SUCCESS) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "GenieDialog_free status=%d", static_cast<int>(status));
            // Keep the runtime mapped if Genie could not release its dialog.
            __atomic_store_n(&runtimeReleaseFailed, true, __ATOMIC_RELEASE);
            free(session);
            return false;
        }
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "GENIE_DIALOG_RELEASED");
    }
    if (session->library) dlclose(session->library);
    free(session);
    return true;
}

char* readUtf8(JNIEnv* env, jbyteArray input) {
    const auto bytes = env->GetArrayLength(input);
    auto* value = static_cast<char*>(calloc(static_cast<size_t>(bytes) + 1, 1));
    if (!value) {
        fail(env, "Could not allocate Genie input");
        return nullptr;
    }
    env->GetByteArrayRegion(input, 0, bytes, reinterpret_cast<jbyte*>(value));
    if (env->ExceptionCheck()) {
        free(value);
        return nullptr;
    }
    return value;
}

bool configureDspPath(const char* skelDirectory) {
    const char* paths = getenv("ADSP_LIBRARY_PATH");
    const size_t pathLength = strlen(skelDirectory);
    if (paths && strncmp(paths, skelDirectory, pathLength) == 0 && paths[pathLength] == ';') return true;
    constexpr auto defaults = "/usr/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp";
    char* combined = nullptr;
    if (asprintf(&combined, "%s;%s%s%s", skelDirectory, paths ? paths : "", paths ? ";" : "", defaults) < 0) {
        return false;
    }
    const bool success = setenv("ADSP_LIBRARY_PATH", combined, 1) == 0;
    free(combined);
    return success;
}

void collect(const char* text, GenieDialog_SentenceCode_t code, const void* data) {
    auto* response = static_cast<Response*>(const_cast<void*>(data));
    if (text && !response->overflow) {
        const auto size = strlen(text);
        if (size > sizeof(response->text) - response->length) {
            response->overflow = true;
        } else {
            memcpy(response->text + response->length, text, size);
            response->length += size;
        }
    }
    if (code == GENIE_DIALOG_SENTENCE_END || code == GENIE_DIALOG_SENTENCE_COMPLETE) response->complete = true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_cafe_1kiosk_1ai_llm_GenieNative_open(
    JNIEnv* env, jobject, jbyteArray config, jbyteArray dspDir, jobject onLibraryLoaded) {
    if (__atomic_load_n(&runtimeReleaseFailed, __ATOMIC_ACQUIRE)) {
        fail(env, "Genie could not release an earlier dialog; restart the app");
        return 0;
    }
    auto* session = static_cast<Session*>(calloc(1, sizeof(Session)));
    if (!session) {
        fail(env, "Could not allocate Genie session");
        return 0;
    }

    char* dsp = readUtf8(env, dspDir);
    if (!dsp) {
        release(session);
        return 0;
    }
    const bool configured = configureDspPath(dsp);
    if (configured) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "NATIVE_LIBRARY_LOAD ADSP_LIBRARY_PATH=%s",
                            getenv("ADSP_LIBRARY_PATH"));
    }
    free(dsp);
    if (!configured) {
        fail(env, "Could not set ADSP_LIBRARY_PATH");
        release(session);
        return 0;
    }

    session->library = dlopen("libGenie.so", RTLD_NOW | RTLD_LOCAL);
    if (!session->library || !loadFunctions(session)) {
        const char* error = dlerror();
        fail(env, error ? error : "Genie dialog API missing");
        release(session);
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "GENIE_LIBRARY_LOADED libGenie.so");
    const auto callbackType = env->GetObjectClass(onLibraryLoaded);
    const auto callback = env->GetMethodID(callbackType, "run", "()V");
    if (callback) env->CallVoidMethod(onLibraryLoaded, callback);
    if (env->ExceptionCheck()) {
        release(session);
        return 0;
    }

    char* json = readUtf8(env, config);
    if (!json) {
        release(session);
        return 0;
    }
    GenieDialogConfig_Handle_t configuration = nullptr;
    auto status = session->createConfig(json, &configuration);
    if (status != GENIE_STATUS_SUCCESS) {
        failStatus(env, "GenieDialogConfig_createFromJson", status);
    } else if (!configuration) {
        fail(env, "GenieDialogConfig_createFromJson returned a null configuration");
    } else {
        status = session->createDialog(configuration, &session->dialog);
        if (status != GENIE_STATUS_SUCCESS) failStatus(env, "GenieDialog_create", status);
        else if (!session->dialog) fail(env, "GenieDialog_create returned a null dialog");
    }
    if (configuration) session->freeConfig(configuration);
    free(json);
    if (env->ExceptionCheck()) {
        release(session);
        return 0;
    }
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_cafe_1kiosk_1ai_llm_GenieNative_query(JNIEnv* env, jobject, jlong handle, jbyteArray input) {
    auto* session = reinterpret_cast<Session*>(handle);
    char* prompt = readUtf8(env, input);
    if (!prompt) return nullptr;

    Response response{};
    const auto status = session->query(session->dialog, prompt, GENIE_DIALOG_SENTENCE_COMPLETE, collect, &response);
    free(prompt);
    if (status != GENIE_STATUS_SUCCESS) {
        failStatus(env, "GenieDialog_query", status);
        return nullptr;
    }
    if (!response.complete || response.overflow || response.length == 0) {
        fail(env, "Genie emitted an incomplete, empty or oversized response");
        return nullptr;
    }
    const auto bytes = static_cast<jsize>(response.length);
    jbyteArray output = env->NewByteArray(bytes);
    if (output) env->SetByteArrayRegion(output, 0, bytes, reinterpret_cast<const jbyte*>(response.text));
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_cafe_1kiosk_1ai_llm_GenieNative_reset(JNIEnv* env, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    const auto status = session->resetDialog(session->dialog);
    if (status != GENIE_STATUS_SUCCESS) failStatus(env, "GenieDialog_reset", status);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_cafe_1kiosk_1ai_llm_GenieNative_abort(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    return session->signal(session->dialog, GENIE_DIALOG_ACTION_ABORT);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_cafe_1kiosk_1ai_llm_GenieNative_close(JNIEnv*, jobject, jlong handle) {
    return release(reinterpret_cast<Session*>(handle)) ? JNI_TRUE : JNI_FALSE;
}
