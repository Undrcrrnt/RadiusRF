#include "emptyset_iq_ring.h"
#include <airspyhf.h>

static IqRing g_ring;
static std::mutex g_dev_mu;
static airspyhf_device_t *g_dev = nullptr;
static bool g_streaming = false;
static int g_rate = 0;
static int64_t g_frequency = 0;

static int rx_callback(airspyhf_transfer_t *transfer) {
    if (transfer == nullptr || transfer->samples == nullptr || transfer->sample_count <= 0) return 0;
    const float *samples = reinterpret_cast<const float *>(transfer->samples);
    g_ring.push_float_pairs(samples, transfer->sample_count);
    return 0;
}

static void shutdown_device() {
    airspyhf_device_t *dev = nullptr;
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
    if (dev != nullptr && streaming) airspyhf_stop(dev);
    if (dev != nullptr) airspyhf_close(dev);
    std::lock_guard<std::mutex> lock(g_ring.ring_mu);
    g_ring.clear_locked();
    g_ring.cv.notify_all();
}

static uint32_t chosen_rate(airspyhf_device_t *dev, uint32_t wanted) {
    uint32_t count = 0;
    if (airspyhf_get_samplerates(dev, &count, 0) != AIRSPYHF_SUCCESS || count == 0 || count > 32) return wanted;
    uint32_t rates[32];
    if (airspyhf_get_samplerates(dev, rates, count) != AIRSPYHF_SUCCESS) return wanted;
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
Java_app_radiusrf_features_sdr_domain_NativeAirspyHf_open(JNIEnv *env, jobject, jint fd) {
    shutdown_device();
    airspyhf_device_t *dev = nullptr;
    if (airspyhf_open_fd(&dev, fd) != AIRSPYHF_SUCCESS || dev == nullptr) {
        return env->NewStringUTF("Airspy HF+ did not start");
    }
    airspyhf_set_lib_dsp(dev, 1);
    std::lock_guard<std::mutex> lock(g_dev_mu);
    g_dev = dev;
    g_ring.open.store(true);
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_radiusrf_features_sdr_domain_NativeAirspyHf_configure(
        JNIEnv *env, jobject, jlong freq_hz, jint sample_rate, jint gain_tenths, jint preamp, jint bias) {
    airspyhf_device_t *dev = nullptr;
    bool restart = false;
    bool retune = false;
    {
        std::lock_guard<std::mutex> lock(g_dev_mu);
        if (g_dev == nullptr) return env->NewStringUTF("Airspy HF+ is not open");
        if (freq_hz < 9000LL || freq_hz > 260000000LL) {
            return env->NewStringUTF("Airspy HF+ tunes from 9 kHz to 260 MHz");
        }
        dev = g_dev;
        restart = g_streaming && sample_rate != g_rate;
        retune = g_streaming && freq_hz != g_frequency;
    }
    if (restart) {
        airspyhf_stop(dev);
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
        if (airspyhf_set_samplerate(dev, rate) != AIRSPYHF_SUCCESS) {
            return env->NewStringUTF("Sample rate was rejected");
        }
        std::lock_guard<std::mutex> lock(g_dev_mu);
        g_rate = sample_rate;
    }
    airspyhf_set_hf_lna(dev, preamp ? 1 : 0);
    airspyhf_set_bias_tee(dev, bias ? 1 : 0);
    if (gain_tenths < 0) {
        airspyhf_set_hf_agc(dev, 1);
    } else {
        airspyhf_set_hf_agc(dev, 0);
        int att = gain_tenths / 10;
        if (att < 0) att = 0;
        if (att > 48) att = 48;
        airspyhf_set_hf_att(dev, static_cast<uint8_t>(att / 6));
    }
    if (airspyhf_set_freq(dev, static_cast<uint32_t>(freq_hz)) != AIRSPYHF_SUCCESS) {
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
        if (airspyhf_start(dev, rx_callback, nullptr) != AIRSPYHF_SUCCESS) {
            return env->NewStringUTF("Airspy HF+ receive did not start");
        }
        std::lock_guard<std::mutex> lock(g_dev_mu);
        g_streaming = true;
    }
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_radiusrf_features_sdr_domain_NativeAirspyHf_read(JNIEnv *env, jobject, jbyteArray buffer) {
    return g_ring.read(env, buffer);
}

extern "C" JNIEXPORT void JNICALL
Java_app_radiusrf_features_sdr_domain_NativeAirspyHf_close(JNIEnv *, jobject) {
    shutdown_device();
}
