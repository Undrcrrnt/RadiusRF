#include <android/log.h>
#include <jni.h>
#include <libusb.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "DeviceConfig.h"
#include "IRadio.h"
#include "RxPacket.h"
#include "SelectedChannel.h"
#include "UsbOpen.h"
#include "UsbTransport.h"
#include "WiFiDriver.h"
#include "logger.h"
#include "mt7612u/Mt7612uUsbIds.h"

#define TAG "emptyset_rx"

namespace {

JavaVM *g_vm = nullptr;
std::mutex g_mu;
libusb_context *g_ctx = nullptr;
libusb_device_handle *g_handle = nullptr;
std::shared_ptr<devourer::UsbDeviceLock> g_lock;
std::unique_ptr<IRadio> g_radio;
std::thread g_rx;
std::mutex g_thread_mu;
int g_iface = 0;
std::atomic<int> g_channel{1};
std::atomic<bool> g_running{false};
jobject g_sink = nullptr;
jmethodID g_on_frame = nullptr;
jmethodID g_on_error = nullptr;
jmethodID g_on_ready = nullptr;

void log_err(const char *msg) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", msg);
}

void clear_jni(JNIEnv *env) {
    if (env != nullptr && env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

thread_local bool t_attached = false;

void detach_jni_thread() {
    if (!t_attached || g_vm == nullptr) {
        return;
    }
    t_attached = false;
    g_vm->DetachCurrentThread();
}

JNIEnv *env_for() {
    JNIEnv *env = nullptr;
    if (g_vm == nullptr) {
        return nullptr;
    }
    const jint state = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (state == JNI_OK) {
        return env;
    }
    if (state == JNI_EDETACHED &&
        g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        t_attached = true;
        return env;
    }
    return nullptr;
}

void join_rx_thread() {
    std::lock_guard<std::mutex> thread_hold(g_thread_mu);
    if (g_rx.joinable()) {
        g_rx.join();
    }
}

void deliver_error(const std::string &msg) {
    log_err(msg.c_str());
    JNIEnv *env = env_for();
    if (env == nullptr || g_sink == nullptr || g_on_error == nullptr) {
        return;
    }
    jstring jmsg = env->NewStringUTF(msg.c_str());
    if (jmsg == nullptr) {
        clear_jni(env);
        return;
    }
    env->CallVoidMethod(g_sink, g_on_error, jmsg);
    clear_jni(env);
    env->DeleteLocalRef(jmsg);
}

void deliver_ready() {
    JNIEnv *env = env_for();
    if (env == nullptr || g_sink == nullptr || g_on_ready == nullptr) {
        return;
    }
    env->CallVoidMethod(g_sink, g_on_ready);
    clear_jni(env);
}

bool is_scan_or_deauth(const uint8_t *data, size_t len) {
    size_t off = 0;
    if (len >= 8 && data[0] == 0 && data[1] == 0) {
        const size_t header = static_cast<size_t>(data[2]) | (static_cast<size_t>(data[3]) << 8);
        if (header >= 8 && header < len) {
            off = header;
        }
    }
    if (len - off < 24) {
        return false;
    }
    const uint8_t fc = data[off];
    const int type = (fc >> 2) & 0x3;
    const int subtype = (fc >> 4) & 0xF;
    // Beacon, probe response, disassoc, deauth. Data frames stay on the adapter.
    return type == 0 && (subtype == 5 || subtype == 8 || subtype == 10 || subtype == 12);
}

void on_packet(const Packet &packet) {
    if (!g_running.load()) {
        return;
    }
    const auto data = packet.Data;
    if (!is_scan_or_deauth(data.data(), data.size())) {
        return;
    }
    JNIEnv *env = env_for();
    if (env == nullptr || g_sink == nullptr || g_on_frame == nullptr) {
        return;
    }
    const jsize n = static_cast<jsize>(data.size() > 4096 ? 4096 : data.size());
    jbyteArray arr = env->NewByteArray(n);
    if (arr == nullptr) {
        clear_jni(env);
        return;
    }
    env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(data.data()));
    if (env->ExceptionCheck()) {
        clear_jni(env);
        env->DeleteLocalRef(arr);
        return;
    }
    const jint rssi = static_cast<jint>(packet.RxAtrib.rssi[0]);
    env->CallVoidMethod(g_sink, g_on_frame, arr, rssi, static_cast<jint>(g_channel.load()));
    clear_jni(env);
    env->DeleteLocalRef(arr);
}

void close_usb_locked() {
    g_lock.reset();
    if (g_handle != nullptr) {
        libusb_release_interface(g_handle, g_iface);
        libusb_close(g_handle);
        g_handle = nullptr;
    }
    if (g_ctx != nullptr) {
        libusb_exit(g_ctx);
        g_ctx = nullptr;
    }
    g_iface = 0;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_emptyset_detector_radio_NativeRx_nativeStart(
    JNIEnv *env, jclass, jint fd, jstring lock_dir, jint channel, jobject sink) {
    try {
        std::unique_lock<std::mutex> hold(g_mu);
        if (g_running) {
            return env->NewStringUTF("native RX already running");
        }
        if (g_rx.joinable()) {
            hold.unlock();
            join_rx_thread();
            hold.lock();
            if (g_running) {
                return env->NewStringUTF("native RX already running");
            }
            g_radio.reset();
            close_usb_locked();
        }
        if (fd < 0) {
            return env->NewStringUTF("invalid USB file descriptor");
        }
        if (sink == nullptr) {
            return env->NewStringUTF("missing JNI sink");
        }

        if (g_sink != nullptr) {
            env->DeleteGlobalRef(g_sink);
            g_sink = nullptr;
        }
        g_sink = env->NewGlobalRef(sink);
        auto fail_start = [&](const char *message) -> jstring {
            g_radio.reset();
            close_usb_locked();
            if (g_sink != nullptr) {
                env->DeleteGlobalRef(g_sink);
                g_sink = nullptr;
            }
            return env->NewStringUTF(message);
        };
        jclass sink_cls = env->GetObjectClass(sink);
        g_on_frame = env->GetMethodID(sink_cls, "onFrame", "([BII)V");
        g_on_error = env->GetMethodID(sink_cls, "onNativeError", "(Ljava/lang/String;)V");
        g_on_ready = env->GetMethodID(sink_cls, "onReady", "()V");
        env->DeleteLocalRef(sink_cls);
        if (g_on_frame == nullptr || g_on_error == nullptr || g_on_ready == nullptr) {
            env->ExceptionClear();
            return fail_start("JNI sink methods missing");
        }

        std::string lock_path;
        if (lock_dir != nullptr) {
            const char *lock_chars = env->GetStringUTFChars(lock_dir, nullptr);
            if (lock_chars) {
                lock_path = lock_chars;
                env->ReleaseStringUTFChars(lock_dir, lock_chars);
            }
        }

        (void)libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
        int r = libusb_init(&g_ctx);
        if (r < 0 || g_ctx == nullptr) {
            g_ctx = nullptr;
            return fail_start("libusb_init failed");
        }

        r = libusb_wrap_sys_device(g_ctx, static_cast<intptr_t>(fd), &g_handle);
        if (r < 0 || g_handle == nullptr) {
            libusb_exit(g_ctx);
            g_ctx = nullptr;
            g_handle = nullptr;
            return fail_start("libusb_wrap_sys_device failed");
        }
        libusb_set_auto_detach_kernel_driver(g_handle, 1);

        auto logger = std::make_shared<Logger>();
        g_iface = devourer::find_wifi_interface(g_handle);
        if (g_iface < 0) {
            g_iface = 0;
        }
        r = devourer::claim_interface_then_reset(
            g_handle, g_iface, logger, /*do_reset=*/false, g_lock, lock_path);
        if (r < 0) {
            return fail_start("USB claim failed (is the adapter already in use?)");
        }

        devourer::DeviceConfig cfg{};
        cfg.usb.lock_dir = lock_path;
        cfg.usb.rx_zerocopy = false;

        uint16_t vid = 0;
        uint16_t pid = 0;
        if (libusb_device *dev = libusb_get_device(g_handle)) {
            libusb_device_descriptor desc{};
            if (libusb_get_device_descriptor(dev, &desc) == 0) {
                vid = desc.idVendor;
                pid = desc.idProduct;
            }
        }
        const bool mt7612 = mt7612u::is_usb_id(vid, pid);
        uint8_t chip_id = 0;
        if (!mt7612) {
            const int chip_rc = libusb_control_transfer(
                g_handle, REALTEK_USB_VENQT_READ, 5, 0x00FC, 0, &chip_id, 1, USB_TIMEOUT);
            if (chip_rc != 1) {
                return fail_start("SYS_CFG2 chip-id read failed. Unplug, grant USB, and start again.");
            }
        }

        WiFiDriver driver(logger);
        g_radio = driver.CreateRadio(g_handle, g_ctx, g_lock, cfg);
        if (!g_radio) {
            char msg[128];
            if (mt7612) {
                std::snprintf(msg, sizeof(msg),
                              "MT7612U %04x:%04x was recognized but did not start",
                              vid, pid);
            } else {
                std::snprintf(msg, sizeof(msg),
                              "Adapter did not start after chip-id 0x%02x (Jaguar1 is 0x04, 0x05, or 0x08)",
                              chip_id);
            }
            return fail_start(msg);
        }

        const uint8_t start_ch = static_cast<uint8_t>(channel <= 0 ? 1 : channel);
        g_channel = start_ch;
        g_running = true;
        SelectedChannel sel{};
        sel.Channel = start_ch;
        sel.ChannelOffset = 0;
        sel.ChannelWidth = CHANNEL_WIDTH_20;
        IRadio *radio = g_radio.get();

        {
            std::lock_guard<std::mutex> thread_hold(g_thread_mu);
            g_rx = std::thread([sel, radio]() {
                try {
                    deliver_ready();
                    radio->Init(on_packet, sel);
                } catch (const std::exception &ex) {
                    deliver_error(std::string("USB adapter RX failed: ") + ex.what());
                } catch (...) {
                    deliver_error("USB adapter RX failed");
                }
                g_running = false;
                detach_jni_thread();
            });
        }

        return env->NewStringUTF("");
    } catch (const std::exception &ex) {
        g_running = false;
        try {
            close_usb_locked();
        } catch (...) {
        }
        if (g_sink != nullptr) {
            env->DeleteGlobalRef(g_sink);
            g_sink = nullptr;
        }
        return env->NewStringUTF(ex.what());
    } catch (...) {
        g_running = false;
        try {
            close_usb_locked();
        } catch (...) {
        }
        if (g_sink != nullptr) {
            env->DeleteGlobalRef(g_sink);
            g_sink = nullptr;
        }
        return env->NewStringUTF("native start failed");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_emptyset_detector_radio_NativeRx_nativeSetChannel(JNIEnv *, jclass, jint channel) {
    try {
        if (channel <= 0 || channel > 253) {
            return;
        }
        g_channel = channel;
        std::lock_guard<std::mutex> hold(g_mu);
        if (!g_radio || !g_running) {
            return;
        }
        g_radio->FastRetune(static_cast<uint8_t>(channel), true);
    } catch (const std::exception &ex) {
        log_err(ex.what());
    } catch (...) {
        log_err("nativeSetChannel failed");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_emptyset_detector_radio_NativeRx_nativeStop(JNIEnv *env, jclass) {
    try {
        std::unique_lock<std::mutex> lock(g_mu);
        g_running = false;
        if (g_radio) {
            g_radio->StopRxLoop();
        }
        lock.unlock();
        join_rx_thread();
        lock.lock();
        if (g_radio) {
            try {
                g_radio->Stop();
            } catch (...) {
            }
            g_radio.reset();
        }
        close_usb_locked();
        if (g_sink != nullptr) {
            env->DeleteGlobalRef(g_sink);
            g_sink = nullptr;
        }
        g_on_frame = nullptr;
        g_on_error = nullptr;
        g_on_ready = nullptr;
    } catch (...) {
        log_err("nativeStop failed");
    }
}
