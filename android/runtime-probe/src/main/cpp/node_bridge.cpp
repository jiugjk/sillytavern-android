// SPDX-License-Identifier: AGPL-3.0-only
#include <jni.h>
#include <android/log.h>
#include <node.h>
#include <atomic>
#include <cstring>
#include <string>
#include <thread>
#include <vector>
#include <unistd.h>

namespace {
std::atomic<bool> started{false};
void redirect_logs() {
    int descriptors[2];
    if (pipe(descriptors) != 0) return;
    dup2(descriptors[1], STDOUT_FILENO);
    dup2(descriptors[1], STDERR_FILENO);
    close(descriptors[1]);
    std::thread([fd = descriptors[0]] {
        char buffer[2048];
        ssize_t count;
        while ((count = read(fd, buffer, sizeof(buffer) - 1)) > 0) {
            buffer[count] = '\0';
            __android_log_write(ANDROID_LOG_INFO, "STNode26", buffer);
        }
        close(fd);
    }).detach();
}
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_stshell_probe_NativeNode_start(JNIEnv* env, jobject, jobjectArray arguments) {
    // node::Start is a once-per-process entry point. Restart the service process,
    // not the runtime inside the same process.
    if (started.exchange(true)) return 125;
    if (!arguments) return 126;
    const auto count = env->GetArrayLength(arguments);
    if (count < 2 || count > 16) return 126;
    std::vector<std::string> strings;
    strings.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        if (!value) return 126;
        const char* utf8 = env->GetStringUTFChars(value, nullptr);
        if (!utf8) { env->DeleteLocalRef(value); return 126; }
        strings.emplace_back(utf8);
        env->ReleaseStringUTFChars(value, utf8);
        env->DeleteLocalRef(value);
    }
    // libuv/process.title expects argv strings in a contiguous writable area.
    size_t size = 0;
    for (const auto& value : strings) size += value.size() + 1;
    std::vector<char> storage(size);
    std::vector<char*> argv;
    argv.reserve(strings.size() + 1);
    size_t offset = 0;
    for (const auto& value : strings) {
        std::memcpy(storage.data() + offset, value.c_str(), value.size() + 1);
        argv.push_back(storage.data() + offset);
        offset += value.size() + 1;
    }
    argv.push_back(nullptr);
    redirect_logs();
    __android_log_print(ANDROID_LOG_INFO, "STNode26", "Starting embedded Node on pid %d", getpid());
    return node::Start(count, argv.data());
}
