#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <hackrf.h>

static std::mutex g_dev_mu;
static std::mutex g_ring_mu;
static std::condition_variable g_cv;
static hackrf_device *g_dev = nullptr;
static bool g_streaming = false;
static int g_rate = 0;
static std::atomic<bool> g_open{false};
static uint8_t g_ring[1 << 20];
static int g_head = 0;
static int g_count = 0;

static void ring_clear_locked() {
    g_head = 0;
    g_count = 0;
}

static int rx_callback(hackrf_transfer *transfer) {
    if (transfer == nullptr || transfer->buffer == nullptr || transfer->valid_length <= 0) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(g_ring_mu);
    const int cap = static_cast<int>(sizeof(g_ring));
    int take = transfer->valid_length;
    if (take > cap - g_count) take = cap - g_count;
    const uint8_t *src = transfer->buffer;
    while (take > 0) {
        int space = cap - g_head;
        int n = take < space ? take : space;
        memcpy(g_ring + g_head, src, static_cast<size_t>(n));
        g_head = (g_head + n) % cap;
        g_count += n;
        src += n;
        take -= n;
    }
    g_cv.notify_one();
    return 0;
}

static void shutdown_device() {
    hackrf_device *dev = nullptr;
    bool streaming = false;
    {
        std::lock_guard<std::mutex> lock(g_dev_mu);
        dev = g_dev;
        streaming = g_streaming;
        g_dev = nullptr;
        g_streaming = false;
        g_rate = 0;
        g_open.store(false);
    }
    if (dev != nullptr && streaming) {
        hackrf_stop_rx(dev);
    }
    if (dev != nullptr) {
        hackrf_close(dev);
        hackrf_exit();
    }
    std::lock_guard<std::mutex> lock(g_ring_mu);
    ring_clear_locked();
    g_cv.notify_all();
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_radiusrf_features_sdr_domain_NativeHackrf_open(
        JNIEnv *env, jobject, jint fd) {
    shutdown_device();
    hackrf_device *dev = nullptr;
    int result = hackrf_open_by_fd(&dev, fd);
    if (result != HACKRF_SUCCESS || dev == nullptr) {
        return env->NewStringUTF("HackRF did not start");
    }
    std::lock_guard<std::mutex> lock(g_dev_mu);
    g_dev = dev;
    g_open.store(true);
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_radiusrf_features_sdr_domain_NativeHackrf_configure(
        JNIEnv *env, jobject, jlong freq_hz, jint sample_rate, jint lna, jint vga, jint amp) {
    hackrf_device *dev = nullptr;
    bool restart = false;
    {
        std::lock_guard<std::mutex> lock(g_dev_mu);
        if (g_dev == nullptr) return env->NewStringUTF("HackRF is not open");
        if (freq_hz < 1000000LL || freq_hz > 6000000000LL) {
            return env->NewStringUTF("HackRF tunes from 1 MHz to 6 GHz");
        }
        dev = g_dev;
        restart = g_streaming && sample_rate != g_rate;
    }
    if (restart) {
        hackrf_stop_rx(dev);
        {
            std::lock_guard<std::mutex> lock(g_dev_mu);
            g_streaming = false;
        }
        std::lock_guard<std::mutex> lock(g_ring_mu);
        ring_clear_locked();
    }
    bool streaming = false;
    {
        std::lock_guard<std::mutex> lock(g_dev_mu);
        streaming = g_streaming;
    }
    if (!streaming) {
        int result = hackrf_set_sample_rate(dev, static_cast<double>(sample_rate));
        if (result != HACKRF_SUCCESS) return env->NewStringUTF("Sample rate was rejected");
        std::lock_guard<std::mutex> lock(g_dev_mu);
        g_rate = sample_rate;
    }
    int lna_gain = lna - (lna % 8);
    if (lna_gain < 0) lna_gain = 0;
    if (lna_gain > 40) lna_gain = 40;
    int vga_gain = vga - (vga % 2);
    if (vga_gain < 0) vga_gain = 0;
    if (vga_gain > 62) vga_gain = 62;
    hackrf_set_lna_gain(dev, static_cast<uint32_t>(lna_gain));
    hackrf_set_vga_gain(dev, static_cast<uint32_t>(vga_gain));
    hackrf_set_amp_enable(dev, amp ? 1 : 0);
    int result = hackrf_set_freq(dev, static_cast<uint64_t>(freq_hz));
    if (result != HACKRF_SUCCESS) return env->NewStringUTF("Frequency was rejected");
    {
        std::lock_guard<std::mutex> lock(g_dev_mu);
        streaming = g_streaming;
    }
    if (!streaming) {
        result = hackrf_start_rx(dev, rx_callback, nullptr);
        if (result != HACKRF_SUCCESS) return env->NewStringUTF("HackRF receive did not start");
        std::lock_guard<std::mutex> lock(g_dev_mu);
        g_streaming = true;
    }
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_radiusrf_features_sdr_domain_NativeHackrf_read(
        JNIEnv *env, jobject, jbyteArray buffer) {
    jsize len = env->GetArrayLength(buffer);
    if (len < 512) return -1;
    std::unique_lock<std::mutex> lock(g_ring_mu);
    if (!g_open.load()) return -1;
    if (g_count < 512) {
        g_cv.wait_for(lock, std::chrono::milliseconds(250));
    }
    if (!g_open.load() || g_count < 512) return 0;
    int n = g_count < len ? g_count : len;
    jbyte *bytes = env->GetByteArrayElements(buffer, nullptr);
    const int cap = static_cast<int>(sizeof(g_ring));
    int tail = (g_head - g_count + cap) % cap;
    int left = n;
    int dst = 0;
    while (left > 0) {
        int chunk = cap - tail;
        if (chunk > left) chunk = left;
        memcpy(bytes + dst, g_ring + tail, static_cast<size_t>(chunk));
        tail = (tail + chunk) % cap;
        dst += chunk;
        left -= chunk;
    }
    g_count -= n;
    env->ReleaseByteArrayElements(buffer, bytes, 0);
    return n;
}

extern "C" JNIEXPORT void JNICALL
Java_app_radiusrf_features_sdr_domain_NativeHackrf_close(JNIEnv *, jobject) {
    shutdown_device();
}
