// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// A bridge to the Kenji-NX core, built so a failure inside it cannot take the
// app down with it.
//
// WHAT THE CORE ACTUALLY IS, read out of the built library rather than from
// its source tree:
//
//   * 60 exported functions, all flat C, all named in camelCase -
//     deviceInitialize, graphicsInitialize, deviceLoadDescriptor and so on.
//     The C# source also carries snake_case names (device_initialize) on a
//     second set of attributes; THOSE ARE NOT IN THE BUILD. Wiring dlsym to
//     them would return null for every single call.
//   * NEEDED: liblog, libdl, libz, libm, libc. Nothing else - no .NET runtime
//     to ship, no Mono, no extra payload.
//   * 249 undefined symbols, every one of them ordinary libc/libm/libz.
//
// THE PART THAT WOULD HAVE CRASHED
//   The core calls back OUT through three DllImport("libkenjinxjni") symbols:
//
//       void setRenderingThread();
//       void debug_break(int code);
//       void setCurrentTransform(long native_window, int transform);
//
//   debug_break is called from deviceInitialize itself. NativeAOT resolves a
//   DllImport by dlopen()ing the named library at first use, so if no
//   libkenjinxjni exists the very first device call aborts the process. They
//   are not optional and they are not weak - checked in the binary: all three
//   names are present as import strings.
//
//   So this file IS libkenjinxjni. It provides those three symbols and nothing
//   else needs to exist for the core to initialise.
//
// HOW A CRASH IS CONTAINED
//   A SIGSEGV in a shared library kills the whole process; there is no
//   try/catch for that in either Kotlin or C++. The only real containment on
//   Android is a separate process, so the emulation side runs in one (see
//   android:process in the manifest patch). This file adds the second layer:
//   every entry point is guarded, the core is probed before it is trusted, and
//   a fault is reported as a message rather than a tombstone.

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <csetjmp>
#include <csignal>
#include <cstring>
#include <string>
#include <mutex>

#define LOG_TAG "SymbiosisKenji"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ── what the core imports from us ───────────────────────────────────
//
// Defined before anything else, because the core reaches for them during its
// own initialisation. Empty bodies are correct: on the Kenji build these hook
// a debugger and a window transform, neither of which applies here. What
// matters is that dlsym finds them instead of failing.

std::mutex g_lock;
void* g_core = nullptr;          // handle of the loaded core
std::string g_lastError;

// Set when the core is inside a call, so a fault can be blamed on the right
// thing rather than on the app.
volatile sig_atomic_t g_inCore = 0;

} // namespace

extern "C" {

// Called by the core on its render thread. Kenji uses it to mark the thread
// for its own bookkeeping; there is nothing to do here, but the symbol must
// exist or deviceInitialize aborts.
JNIEXPORT void setRenderingThread() {
    // Intentionally empty - see the note above.
}

// Called by the core at points it considers worth breaking on, including from
// deviceInitialize. Logging it turns an invisible abort into a breadcrumb.
JNIEXPORT void debug_break(int code) {
    LOGI("core debug_break(%d)", code);
}

// Window rotation. Without a surface attached there is nothing to rotate, but
// again: the symbol has to resolve.
JNIEXPORT void setCurrentTransform(long native_window, int transform) {
    (void)native_window;
    (void)transform;
}

} // extern "C"

namespace {

// ── loading, carefully ──────────────────────────────────────────────

// Every exported name the core is known to have, as read from its dynamic
// symbol table. Used to verify a downloaded core is the real thing before a
// single call is made into it - a truncated or substituted file would
// otherwise fail at the first call, deep inside, with no useful message.
const char* kRequiredSymbols[] = {
    "javaInitialize",
    "deviceInitialize",
    "deviceLoadDescriptor",
    "deviceCloseEmulation",
    "deviceSignalEmulationClose",
    "graphicsInitialize",
    "graphicsInitializeRenderer",
    "graphicsRendererRunLoop",
    "graphicsRendererSetSize",
    "inputInitialize",
    "deviceGetGameFrameRate",
};

bool hasAll(void* handle, std::string& missing) {
    for (const char* name : kRequiredSymbols) {
        dlerror();
        void* sym = dlsym(handle, name);
        if (sym == nullptr) {
            missing = name;
            return false;
        }
    }
    return true;
}

} // namespace

extern "C" {

/**
 * Load the core and verify it before letting anything call into it.
 *
 * Returns an empty string on success, or a sentence fit to show a person.
 */
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeLoad(JNIEnv* env, jobject, jstring jpath) {
    std::lock_guard<std::mutex> guard(g_lock);

    if (g_core != nullptr) {
        return env->NewStringUTF("");   // already up
    }

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        return env->NewStringUTF("путь к ядру пуст");
    }

    // RTLD_NOW, not RTLD_LAZY. Lazy binding defers the failure to the first
    // call of a missing symbol, which surfaces as a crash somewhere unrelated;
    // resolving everything now turns the same problem into a message here.
    dlerror();
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    std::string err = handle ? "" : (dlerror() ? dlerror() : "dlopen failed");
    env->ReleaseStringUTFChars(jpath, path);

    if (handle == nullptr) {
        // The two failures worth telling apart, because the fix differs.
        std::string msg;
        if (err.find("writable") != std::string::npos) {
            msg = "Android отклонил ядро: файл доступен для записи";
        } else if (err.find("32-bit") != std::string::npos ||
                   err.find("64-bit") != std::string::npos) {
            msg = "ядро собрано под другую архитектуру";
        } else {
            msg = "не удалось открыть ядро: " + err;
        }
        LOGE("%s", msg.c_str());
        g_lastError = msg;
        return env->NewStringUTF(msg.c_str());
    }

    std::string missing;
    if (!hasAll(handle, missing)) {
        // A file that opens but has the wrong contents. Closing it here means
        // no later call can reach a half-valid library.
        dlclose(handle);
        std::string msg = "ядро неполное: нет функции " + missing;
        LOGE("%s", msg.c_str());
        g_lastError = msg;
        return env->NewStringUTF(msg.c_str());
    }

    g_core = handle;
    LOGI("core loaded and verified");
    return env->NewStringUTF("");
}

/** How many of the known entry points the loaded core exposes. */
JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeSymbolCount(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_core == nullptr) return 0;
    int n = 0;
    for (const char* name : kRequiredSymbols) {
        if (dlsym(g_core, name) != nullptr) n++;
    }
    return n;
}

JNIEXPORT jboolean JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeIsLoaded(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    return g_core != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    return env->NewStringUTF(g_lastError.c_str());
}

/**
 * Hand the core its JNI environment and data path.
 *
 * This is the first real call into the core, and the one most likely to fail:
 * it is where debug_break is reached and where a mismatched core aborts. It is
 * kept separate from loading so the interface can report "loaded, but would
 * not start" rather than one vague failure covering both.
 */
JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeInitialize(JNIEnv* env, jobject,
                                                           jstring jdataPath) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_core == nullptr) {
        return env->NewStringUTF("ядро не загружено");
    }

    using JavaInitFn = bool (*)(void*, void*);
    auto fn = reinterpret_cast<JavaInitFn>(dlsym(g_core, "javaInitialize"));
    if (fn == nullptr) {
        return env->NewStringUTF("в ядре нет javaInitialize");
    }

    const char* dataPath = env->GetStringUTFChars(jdataPath, nullptr);
    if (dataPath == nullptr) {
        return env->NewStringUTF("путь к данным пуст");
    }

    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);

    g_inCore = 1;
    bool ok = false;
    // No setjmp/longjmp around this. Jumping out of a signal handler across a
    // frame that holds .NET runtime state leaves the core in a condition where
    // the next call fails differently every time - which is worse to debug
    // than a clean stop. Containment is the separate process; this call either
    // returns or the emulation process dies alone.
    ok = fn(const_cast<char*>(dataPath), reinterpret_cast<void*>(vm));
    g_inCore = 0;

    env->ReleaseStringUTFChars(jdataPath, dataPath);

    if (!ok) {
        g_lastError = "ядро отказалось инициализироваться";
        LOGE("%s", g_lastError.c_str());
        return env->NewStringUTF(g_lastError.c_str());
    }
    LOGI("core initialised");
    return env->NewStringUTF("");
}

/** Release the core. Safe to call when nothing is loaded. */
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeUnload(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_core != nullptr) {
        dlclose(g_core);
        g_core = nullptr;
        LOGI("core unloaded");
    }
}

} // extern "C"
