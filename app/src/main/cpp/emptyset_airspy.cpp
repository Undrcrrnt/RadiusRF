#include "emptyset_iq_ring.h"
#include <airspy.h>

static IqRing g_ring;
static std::mutex g_dev_mu;
static airspy_device *g_dev = nullptr;
static bool g_streaming = false;
static int g_rate = 0;
static int64_t g_frequency = 0;

static int rx_callback(airspy_transfer *transfer) {
    if (transfer == nullptr || transfer->samples == nullptr || transfer->sample_count <= 0) return 0;
    if (transfer->sample_type != AIRSPY_SAMPLE_INT16_IQ) return 0;
    g_ring.push_i16_pairs(static_cast<const int16_t *>(transfer->samples), transfer->sample_count);
    return 0;
}

static void shutdown_device() {
    airspy_device *dev = nullptr;
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
    if (dev != nullptr && streaming) airspy_stop_rx(dev);
    if (dev != nullptr) airspy_close(dev);
    std::lock_guard<std::mutex> lock(g_ring.ring_mu);
    g_ring.clear_locked();
    g_ring.cv.notify_all();
}

static uint32_t chosen_rate(airspy_device *dev, uint32_t wanted) {
    uint32_t count = 0;
    if (airspy_get_samplerates(dev, &count, 0) != AIRSPY_SUCCESS || count == 0 || count > 32) return wanted;
    uint32_t rates[32];
    if (airspy_get_samplerates(dev, rates, count) != AIRSPY_SUCCESS) return wanted;
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
Java_app_radiusrf_features_sdr_domain_NativeAirspy_open(JNIEnv *env, jobject, jint fd) {
    shutdown_device();
    airspy_device *dev = nullptr;
    if (airspy_open_fd(&dev, fd) != AIRSPY_SUCCESS || dev == nullptr) {
        return env->NewStringUTF("Airspy did not start");
    }
    airspy_set_sample_type(dev, AIRSPY_SAMPLE_INT16_IQ);
    airspy_set_packing(dev, 0);
    std::lock_guard<std::mutex> lock(g_dev_mu);
    g_dev = dev;
    g_ring.open.store(true);
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_radiusrf_features_sdr_domain_NativeAirspy_configure(
        JNIEnv *env, jobject, jlong freq_hz, jint sample_rate, jint gain_tenths, jint bias) {
    airspy_device *dev = nullptr;
    bool restart = false;
    bool retune = false;
    {
        std::lock_guard<std::mutex> lock(g_dev_mu);
        if (g_dev == nullptr) return env->NewStringUTF("Airspy is not open");
        if (freq_hz < 24000000LL || freq_hz > 1750000000LL) {
            return env->NewStringUTF("Airspy tunes from 24 MHz to 1.75 GHz");
        }
        dev = g_dev;
        restart = g_streaming && sample_rate != g_rate;
        retune = g_streaming && freq_hz != g_frequency;
    }
    if (restart) {
        airspy_stop_rx(dev);
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
        if (airspy_set_samplerate(dev, rate) != AIRSPY_SUCCESS) {
            return env->NewStringUTF("Sample rate was rejected");
        }
        std::lock_guard<std::mutex> lock(g_dev_mu);
        g_rate = sample_rate;
    }
    if (gain_tenths < 0) {
        airspy_set_lna_agc(dev, 1);
        airspy_set_mixer_agc(dev, 1);
    } else {
        airspy_set_lna_agc(dev, 0);
        airspy_set_mixer_agc(dev, 0);
        int linearity = gain_tenths / 10;
        if (linearity < 0) linearity = 0;
        if (linearity > 21) linearity = 21;
        airspy_set_linearity_gain(dev, static_cast<uint8_t>(linearity));
    }
    airspy_set_rf_bias(dev, bias ? 1 : 0);
    if (airspy_set_freq(dev, static_cast<uint32_t>(freq_hz)) != AIRSPY_SUCCESS) {
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
        if (airspy_start_rx(dev, rx_callback, nullptr) != AIRSPY_SUCCESS) {
            return env->NewStringUTF("Airspy receive did not start");
        }
        std::lock_guard<std::mutex> lock(g_dev_mu);
        g_streaming = true;
    }
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_radiusrf_features_sdr_domain_NativeAirspy_read(JNIEnv *env, jobject, jbyteArray buffer) {
    return g_ring.read(env, buffer);
}

extern "C" JNIEXPORT void JNICALL
Java_app_radiusrf_features_sdr_domain_NativeAirspy_close(JNIEnv *, jobject) {
    shutdown_device();
}
