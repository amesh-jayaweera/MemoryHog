#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <string.h>
#include <vector>
#include <android/log.h>

#define LOG_TAG "MemoryHog"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct Allocation {
    void* ptr;
    size_t size;
};

static std::vector<Allocation> allocations;

extern "C" JNIEXPORT jlong JNICALL
Java_com_test_memoryhog_NativeMemory_allocateMB(JNIEnv*, jobject, jint mb) {
    size_t bytes = (size_t)mb * 1024 * 1024;

    // MAP_ANONYMOUS: not backed by any file, pure RAM pages
    // MAP_PRIVATE:   copy-on-write, not shared between processes
    void* ptr = mmap(
        nullptr,
        bytes,
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,   // fd = -1 required for MAP_ANONYMOUS
        0
    );

    if (ptr == MAP_FAILED) {
        LOGE("mmap failed for %d MB", mb);
        return -1;
    }

    // CRITICAL: memset forces the OS to actually commit physical RAM pages.
    // Without this, Linux uses lazy allocation — pages exist virtually but
    // no physical RAM is consumed until written to.
    memset(ptr, 0xAB, bytes);

    allocations.push_back({ptr, bytes});
    LOGI("Allocated %d MB via mmap. Total allocations: %zu", mb, allocations.size());

    return (jlong)bytes;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_test_memoryhog_NativeMemory_getTotalAllocatedBytes(JNIEnv*, jobject) {
    size_t total = 0;
    for (const auto& a : allocations) {
        total += a.size;
    }
    return (jlong)total;
}

extern "C" JNIEXPORT void JNICALL
Java_com_test_memoryhog_NativeMemory_releaseAll(JNIEnv*, jobject) {
    for (const auto& a : allocations) {
        munmap(a.ptr, a.size);
    }
    size_t count = allocations.size();
    allocations.clear();
    LOGI("Released all %zu allocations", count);
}

extern "C" JNIEXPORT void JNICALL
Java_com_test_memoryhog_NativeMemory_releaseLastMB(JNIEnv*, jobject, jint mb) {
    size_t target = (size_t)mb * 1024 * 1024;
    size_t released = 0;

    while (!allocations.empty() && released < target) {
        auto& last = allocations.back();
        released += last.size;
        munmap(last.ptr, last.size);
        allocations.pop_back();
    }
    LOGI("Released ~%d MB", mb);
}
