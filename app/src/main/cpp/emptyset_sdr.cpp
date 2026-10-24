#include <jni.h>
#include <mutex>
#include <rtl-sdr.h>
#include <libusb.h>

static std::mutex g_rtl_mu;
static rtlsdr_dev_t *g_rtl = nullptr;
static int g_bias_on = 0;

extern "C" JNIEXPORT jstring JNICALL
Java_app_radiusrf_features_sdr_domain_NativeRtl_open(
        JNIEnv *env, jobject, jint fd) {
    std::lock_guard<std::mutex> lock(g_rtl_mu);
    if (g_rtl != nullptr) {
        rtlsdr_close(g_rtl);
        g_rtl = nullptr;
    }
    int r = rtlsdr_open_sys_dev(&g_rtl, fd);
    if (r < 0 || g_rtl == nullptr) {
        g_rtl = nullptr;
        return env->NewStringUTF("RTL-SDR did not start");
    }
    g_bias_on = 0;
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_radiusrf_features_sdr_domain_NativeRtl_configure(
        JNIEnv *env, jobject, jlong freq_hz, jint sample_rate, jint gain_tenths, jint ppm,
        jint bias_tee, jint direct_sampling) {
    std::lock_guard<std::mutex> lock(g_rtl_mu);
    if (g_rtl == nullptr) return env->NewStringUTF("RTL-SDR is not open");
    int r = rtlsdr_set_sample_rate(g_rtl, static_cast<uint32_t>(sample_rate));
    if (r < 0) return env->NewStringUTF("Sample rate was rejected");
    int sampling = direct_sampling;
    if (sampling < 0) sampling = freq_hz < 24000000LL ? 1 : 0;
    if (sampling < 0) sampling = 0;
    if (sampling > 2) sampling = 2;
    rtlsdr_set_direct_sampling(g_rtl, sampling);
    // Driving GPIO 0 low on every tune reconfigures the pin and stops some dongles.
    if (bias_tee) {
        rtlsdr_set_bias_tee(g_rtl, 1);
        g_bias_on = 1;
    } else if (g_bias_on) {
        rtlsdr_set_bias_tee(g_rtl, 0);
        g_bias_on = 0;
    }
    if (gain_tenths < 0) {
        rtlsdr_set_tuner_gain_mode(g_rtl, 0);
    } else {
        rtlsdr_set_tuner_gain_mode(g_rtl, 1);
        rtlsdr_set_tuner_gain(g_rtl, gain_tenths);
    }
    rtlsdr_set_freq_correction(g_rtl, ppm);
    r = rtlsdr_set_center_freq(g_rtl, static_cast<uint32_t>(freq_hz));
    if (r < 0) return env->NewStringUTF("Frequency was rejected");
    rtlsdr_reset_buffer(g_rtl);
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_radiusrf_features_sdr_domain_NativeRtl_read(
        JNIEnv *env, jobject, jbyteArray buffer) {
    std::lock_guard<std::mutex> lock(g_rtl_mu);
    if (g_rtl == nullptr) return -1;
    jsize len = env->GetArrayLength(buffer);
    if (len < 512) return -1;
    jbyte *bytes = env->GetByteArrayElements(buffer, nullptr);
    int n = 0;
    int r = rtlsdr_read_sync(g_rtl, bytes, len, &n);
    env->ReleaseByteArrayElements(buffer, bytes, 0);
    if (r == LIBUSB_ERROR_TIMEOUT) return 0;
    if (r < 0) return r;
    return n;
}

extern "C" JNIEXPORT void JNICALL
Java_app_radiusrf_features_sdr_domain_NativeRtl_close(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_rtl_mu);
    if (g_rtl != nullptr) {
        rtlsdr_close(g_rtl);
        g_rtl = nullptr;
    }
    g_bias_on = 0;
}
