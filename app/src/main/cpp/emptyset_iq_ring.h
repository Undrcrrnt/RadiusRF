#pragma once

#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>

struct IqRing {
    std::mutex ring_mu;
    std::condition_variable cv;
    std::atomic<bool> open{false};
    uint8_t data[4 << 20];
    int head = 0;
    int count = 0;

    void clear_locked() {
        head = 0;
        count = 0;
    }

    void push(const uint8_t *src, int n) {
        std::lock_guard<std::mutex> lock(ring_mu);
        const int cap = static_cast<int>(sizeof(data));
        if (n > cap - count) n = cap - count;
        while (n > 0) {
            int space = cap - head;
            int chunk = n < space ? n : space;
            memcpy(data + head, src, static_cast<size_t>(chunk));
            head = (head + chunk) % cap;
            count += chunk;
            src += chunk;
            n -= chunk;
        }
        cv.notify_one();
    }

    void push_i16_pairs(const int16_t *src, int pairs) {
        uint8_t scratch[4096];
        int left = pairs;
        while (left > 0) {
            int n = left < 2048 ? left : 2048;
            for (int i = 0; i < n; i++) {
                scratch[i * 2] = static_cast<uint8_t>(src[i * 2] >> 8);
                scratch[i * 2 + 1] = static_cast<uint8_t>(src[i * 2 + 1] >> 8);
            }
            push(scratch, n * 2);
            src += n * 2;
            left -= n;
        }
    }

    void push_float_pairs(const float *src, int pairs) {
        uint8_t scratch[4096];
        int left = pairs;
        while (left > 0) {
            int n = left < 2048 ? left : 2048;
            for (int i = 0; i < n; i++) {
                float re = src[i * 2];
                float im = src[i * 2 + 1];
                if (re > 1.f) re = 1.f;
                if (re < -1.f) re = -1.f;
                if (im > 1.f) im = 1.f;
                if (im < -1.f) im = -1.f;
                scratch[i * 2] = static_cast<uint8_t>(static_cast<int>(re * 127.f));
                scratch[i * 2 + 1] = static_cast<uint8_t>(static_cast<int>(im * 127.f));
            }
            push(scratch, n * 2);
            src += n * 2;
            left -= n;
        }
    }

    int read(JNIEnv *env, jbyteArray buffer) {
        jsize len = env->GetArrayLength(buffer);
        if (len < 512) return -1;
        std::unique_lock<std::mutex> lock(ring_mu);
        if (!open.load()) return -1;
        if (count < 512) cv.wait_for(lock, std::chrono::milliseconds(250));
        if (!open.load() || count < 512) return 0;
        int n = count < len ? count : len;
        jbyte *bytes = env->GetByteArrayElements(buffer, nullptr);
        const int cap = static_cast<int>(sizeof(data));
        int tail = (head - count + cap) % cap;
        int left = n;
        int dst = 0;
        while (left > 0) {
            int chunk = cap - tail;
            if (chunk > left) chunk = left;
            memcpy(bytes + dst, data + tail, static_cast<size_t>(chunk));
            tail = (tail + chunk) % cap;
            dst += chunk;
            left -= chunk;
        }
        count -= n;
        env->ReleaseByteArrayElements(buffer, bytes, 0);
        return n;
    }
};
