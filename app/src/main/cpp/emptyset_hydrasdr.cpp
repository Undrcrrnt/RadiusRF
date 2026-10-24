#include "emptyset_iq_ring.h"
#include <hydrasdr.h>

static IqRing g_ring;
static std::mutex g_dev_mu;
static hydrasdr_device *g_dev = nullptr;
static bool g_streaming = false;
static int g_rate = 0;
static int64_t g_frequency = 0;

static int rx_callback(hydrasdr_transfer *transfer) {
    if (transfer == nullptr || transfer->samples == nullptr || transfer->sample_count <= 0) return 0;
    if (transfer->sample_type != HYDRASDR_SAMPLE_INT16_IQ) return 0;
    g_ring.push_i16_pairs(static_cast<const int16_t *>(transfer->samples), transfer->sample_count);
    return 0;
}

static void shutdown_device() {
    hydrasdr_device *dev = nullptr;
    bool streaming = false;
    {
        std::lock_guard<std::mutex> lock(g_dev_mu);
        dev = g_dev;
        streaming = g_streaming;
        g_dev = nullptr;
        g_streaming = false;
        g_rate = 0;
        g_frequency = 0;
        g_ring.open.store(false);
    }
    if (dev != nullptr && streaming) hydrasdr_stop_rx(dev);
    if (dev != nullptr) hydrasdr_close(dev);
    std::lock_guard<std::mutex> lock(g_ring.ring_mu);
    g_ring.clear_locked();
    g_ring.cv.notify_all();
}

static uint32_t chosen_rate(hydrasdr_device *dev, uint32_t wanted) {
    uint32_t count = 0;
    if (hydrasdr_get_samplerates(dev, &count, 0) != HYDRASDR_SUCCESS || count == 0 || count > 32) return wanted;
    uint32_t rates[32];
    if (hydrasdr_get_samplerates(dev, rates, count) != HYDRASDR_SUCCESS) return wanted;
    uint32_t best = rates[0];
    uint32_t best_gap = wanted > best ? wanted - best : best - wanted;
    for (uint32_t i = 1; i < count; i++) {
        uint32_t gap = wanted > rates[i] ? wanted - rates[i] : rates[i] - wanted;
        if (gap < best_gap) {
            best = rates[i];
            best_gap = gap;
        }
    }
    return best;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_radiusrf_features_sdr_domain_NativeHydra_open(JNIEnv *env, jobject, jint fd) {
    shutdown_device();
    hydrasdr_device *dev = nullptr;
    if (hydrasdr_open_fd(&dev, fd) != HYDRASDR_SUCCESS || dev == nullptr) {
        return env->NewStringUTF("HydraSDR did not start");
    }
    hydrasdr_set_sample_type(dev, HYDRASDR_SAMPLE_INT16_IQ);
    std::lock_guard<std::mutex> lock(g_dev_mu);
    g_dev = dev;
    g_ring.open.store(true);
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_radiusrf_features_sdr_domain_NativeHydra_configure(
        JNIEnv *env, jobject, jlong freq_hz, jint sample_rate, jint gain_tenths, jint bias) {
    hydrasdr_device *dev = nullptr;
    bool restart = false;
    bool retune = false;
    {
        std::lock_guard<std::mutex> lock(g_dev_mu);
        if (g_dev == nullptr) return env->NewStringUTF("HydraSDR is not open");
        if (freq_hz < 24000000LL || freq_hz > 1800000000LL) {
            return env->NewStringUTF("HydraSDR tunes from 24 MHz to 1.8 GHz");
        }
        dev = g_dev;
        restart = g_streaming && sample_rate != g_rate;
        retune = g_streaming && freq_hz != g_frequency;
    }
    if (restart) {
        hydrasdr_stop_rx(dev);
        std::lock_guard<std::mutex> lock(g_dev_mu);
        g_streaming = false;
        std::lock_guard<std::mutex> ring(g_ring.ring_mu);
        g_ring.clear_locked();
    }
    bool streaming = false;
    {
        std::lock_guard<std::mutex> lock(g_dev_mu);
        streaming = g_streaming;
    }
    if (!streaming) {
        uint32_t rate = chosen_rate(dev, static_cast<uint32_t>(sample_rate));
        if (rate != static_cast<uint32_t>(sample_rate)) {
            return env->NewStringUTF("Requested sample rate is not available");
        }
        if (hydrasdr_set_samplerate(dev, rate) != HYDRASDR_SUCCESS) {
            return env->NewStringUTF("Sample rate was rejected");
        }
        std::lock_guard<std::mutex> lock(g_dev_mu);
        g_rate = sample_rate;
    }
    if (gain_tenths < 0) {
        hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_LNA_AGC, 1);
        hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_MIXER_AGC, 1);
    } else {
        hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_LNA_AGC, 0);
        hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_MIXER_AGC, 0);
        int linearity = gain_tenths / 10;
        if (linearity < 0) linearity = 0;
        if (linearity > 21) linearity = 21;
        hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_LINEARITY, static_cast<uint8_t>(linearity));
    }
    hydrasdr_set_rf_bias(dev, bias ? 1 : 0);
    if (hydrasdr_set_freq(dev, static_cast<uint64_t>(freq_hz)) != HYDRASDR_SUCCESS) {
        return env->NewStringUTF("Frequency was rejected");
    }
    {
        std::lock_guard<std::mutex> lock(g_dev_mu);
        g_frequency = freq_hz;
    }
    if (retune && !restart) {
        std::lock_guard<std::mutex> ring(g_ring.ring_mu);
        g_ring.clear_locked();
    }
    {
        std::lock_guard<std::mutex> lock(g_dev_mu);
        streaming = g_streaming;
    }
    if (!streaming) {
        if (hydrasdr_start_rx(dev, rx_callback, nullptr) != HYDRASDR_SUCCESS) {
            return env->NewStringUTF("HydraSDR receive did not start");
        }
        std::lock_guard<std::mutex> lock(g_dev_mu);
        g_streaming = true;
    }
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_radiusrf_features_sdr_domain_NativeHydra_read(JNIEnv *env, jobject, jbyteArray buffer) {
    return g_ring.read(env, buffer);
}

extern "C" JNIEXPORT void JNICALL
Java_app_radiusrf_features_sdr_domain_NativeHydra_close(JNIEnv *, jobject) {
    shutdown_device();
}
