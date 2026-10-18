#include <android/log.h>
#include <jni.h>
#include <stdio.h>

#include <condition_variable>
#include <mutex>
#include <string>

#include "mt7610u/mt7610u.h"

#define TAG "emptyset_mt76"

namespace {

JavaVM *g_vm = nullptr;
std::mutex g_mu;
std::mutex g_dev;
std::condition_variable g_cv;
int g_inflight = 0;
jobject g_sink = nullptr;
jmethodID g_on_frame = nullptr;
jmethodID g_on_error = nullptr;
jmethodID g_on_ready = nullptr;
jmethodID g_on_status = nullptr;

void clear_jni(JNIEnv *env)
{
	if (env && env->ExceptionCheck())
		env->ExceptionClear();
}

thread_local bool t_attached = false;

void detach_jni_thread()
{
	if (!t_attached || !g_vm)
		return;
	t_attached = false;
	g_vm->DetachCurrentThread();
}

JNIEnv *env_for()
{
	JNIEnv *env = nullptr;
	if (!g_vm)
		return nullptr;
	const jint state = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
	if (state == JNI_OK)
		return env;
	if (state == JNI_EDETACHED && g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
		t_attached = true;
		return env;
	}
	return nullptr;
}

void end_callback()
{
	std::lock_guard<std::mutex> hold(g_mu);
	if (g_inflight > 0)
		g_inflight--;
	g_cv.notify_all();
}

void report(const char *msg)
{
	if (!msg)
		return;
	jobject sink = nullptr;
	jmethodID method = nullptr;
	{
		std::lock_guard<std::mutex> hold(g_mu);
		if (!g_sink || !g_on_status)
			return;
		sink = g_sink;
		method = g_on_status;
		g_inflight++;
	}
	JNIEnv *env = env_for();
	if (!env) {
		end_callback();
		return;
	}
	jstring s = env->NewStringUTF(msg);
	if (!s) {
		clear_jni(env);
		end_callback();
		return;
	}
	env->CallVoidMethod(sink, method, s);
	clear_jni(env);
	env->DeleteLocalRef(s);
	end_callback();
}

bool keep_frame(const uint8_t *frame, int len)
{
	if (!frame || len < 24)
		return false;
	int off = 0;
	if (len >= 8 && frame[0] == 0 && frame[1] == 0) {
		const int header = frame[2] | (frame[3] << 8);
		if (header >= 8 && header < len)
			off = header;
	}
	if (len - off < 24)
		return false;
	const uint8_t fc = frame[off];
	const int type = (fc >> 2) & 0x3;
	const int subtype = (fc >> 4) & 0xF;
	return type == 0 && (subtype == 5 || subtype == 8 || subtype == 10 || subtype == 12);
}

void on_frame(const uint8_t *frame, int len, int rssi, int channel, void *)
{
	if (!keep_frame(frame, len))
		return;
	jobject sink = nullptr;
	jmethodID method = nullptr;
	{
		std::lock_guard<std::mutex> hold(g_mu);
		if (!g_sink || !g_on_frame)
			return;
		sink = g_sink;
		method = g_on_frame;
		g_inflight++;
	}
	JNIEnv *env = env_for();
	if (!env || !frame || len <= 0) {
		end_callback();
		return;
	}
	const jsize n = static_cast<jsize>(len > 4096 ? 4096 : len);
	jbyteArray arr = env->NewByteArray(n);
	if (!arr) {
		clear_jni(env);
		end_callback();
		return;
	}
	env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(frame));
	if (env->ExceptionCheck()) {
		clear_jni(env);
		env->DeleteLocalRef(arr);
		end_callback();
		return;
	}
	env->CallVoidMethod(sink, method, arr, static_cast<jint>(rssi), static_cast<jint>(channel));
	clear_jni(env);
	env->DeleteLocalRef(arr);
	end_callback();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_emptyset_detector_radio_NativePau0a_nativeStart(
    JNIEnv *env, jclass, jint fd, jstring fw_path, jint channel, jobject sink)
{
	env->GetJavaVM(&g_vm);
	mt7610u_set_thread_exit_hook(detach_jni_thread);
	if (sink == nullptr)
		return env->NewStringUTF("missing JNI sink");
	if (fw_path == nullptr)
		return env->NewStringUTF("missing firmware path");

	std::unique_lock<std::mutex> hold(g_mu);
	jobject previous = g_sink;
	g_sink = nullptr;
	g_on_frame = g_on_error = g_on_ready = g_on_status = nullptr;
	g_cv.wait(hold, [] { return g_inflight == 0; });
	if (previous)
		env->DeleteGlobalRef(previous);
	g_sink = env->NewGlobalRef(sink);
	if (!g_sink)
		return env->NewStringUTF("missing JNI sink");
	jclass cls = env->GetObjectClass(sink);
	g_on_frame = env->GetMethodID(cls, "onFrame", "([BII)V");
	g_on_error = env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");
	g_on_ready = env->GetMethodID(cls, "onReady", "()V");
	g_on_status = env->GetMethodID(cls, "onStatus", "(Ljava/lang/String;)V");
	if (env->ExceptionCheck())
		env->ExceptionClear();
	env->DeleteLocalRef(cls);
	if (!g_on_frame || !g_on_error || !g_on_ready) {
		env->ExceptionClear();
		env->DeleteGlobalRef(g_sink);
		g_sink = nullptr;
		return env->NewStringUTF("JNI sink methods missing");
	}

	const char *path = env->GetStringUTFChars(fw_path, nullptr);
	char err[192] = {0};
	hold.unlock();
	report("PAU0A: wrapping USB and loading mt7610u.bin...");
	int rc;
	{
		std::lock_guard<std::mutex> dev(g_dev);
		rc = mt7610u_open(fd, path ? path : "", err, sizeof err);
		if (!rc)
			rc = mt7610u_start_rx(channel <= 0 ? 1 : channel, on_frame, nullptr, err, sizeof err);
	}
	if (path)
		env->ReleaseStringUTFChars(fw_path, path);
	if (rc) {
		std::lock_guard<std::mutex> dev(g_dev);
		mt7610u_stop();
		return env->NewStringUTF(err[0] ? err : "MT7610U open failed");
	}
	if (!mt7610u_is_open())
		return env->NewStringUTF("MT7610U stopped");
	jobject ready_sink = nullptr;
	jmethodID ready_method = nullptr;
	{
		std::lock_guard<std::mutex> ready_hold(g_mu);
		if (g_on_ready && g_sink) {
			ready_sink = g_sink;
			ready_method = g_on_ready;
			g_inflight++;
		}
	}
	if (ready_sink && ready_method) {
		env->CallVoidMethod(ready_sink, ready_method);
		clear_jni(env);
		end_callback();
	}
	char diag[160];
	mt7610u_rx_diag(diag, sizeof diag);
	report(diag);
	return env->NewStringUTF("");
}

extern "C" JNIEXPORT void JNICALL
Java_com_emptyset_detector_radio_NativePau0a_nativeStartPump(JNIEnv *, jclass)
{
	mt7610u_start_pump();
}

extern "C" JNIEXPORT void JNICALL
Java_com_emptyset_detector_radio_NativePau0a_nativeSetChannel(JNIEnv *, jclass, jint channel)
{
	mt7610u_set_channel(channel);
}

extern "C" JNIEXPORT void JNICALL
Java_com_emptyset_detector_radio_NativePau0a_nativePushRx(
    JNIEnv *env, jclass, jbyteArray data, jint length)
{
	if (!data || length <= 0)
		return;
	const jsize n = env->GetArrayLength(data);
	const int use = length < n ? length : n;
	if (use <= 0)
		return;
	jbyte *raw = env->GetByteArrayElements(data, nullptr);
	if (!raw)
		return;
	mt7610u_push_rx(reinterpret_cast<const uint8_t *>(raw), use);
	env->ReleaseByteArrayElements(data, raw, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_emptyset_detector_radio_NativePau0a_nativeRxPoll(JNIEnv *, jclass, jint lastRc)
{
	mt7610u_rx_poll(lastRc);
}

extern "C" JNIEXPORT void JNICALL
Java_com_emptyset_detector_radio_NativePau0a_nativeKickRx(JNIEnv *, jclass)
{
	mt7610u_kick_rx();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_emptyset_detector_radio_NativePau0a_nativeRxStats(JNIEnv *env, jclass)
{
	char buf[160];
	mt7610u_rx_diag(buf, sizeof buf);
	return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_emptyset_detector_radio_NativePau0a_nativeRxEndpoint(JNIEnv *, jclass)
{
	return mt7610u_rx_endpoint();
}

extern "C" JNIEXPORT void JNICALL
Java_com_emptyset_detector_radio_NativePau0a_nativeStop(JNIEnv *env, jclass)
{
	jobject sink = nullptr;
	{
		std::unique_lock<std::mutex> hold(g_mu);
		sink = g_sink;
		g_sink = nullptr;
		g_on_frame = g_on_error = g_on_ready = g_on_status = nullptr;
		g_cv.wait(hold, [] { return g_inflight == 0; });
	}
	{
		std::lock_guard<std::mutex> dev(g_dev);
		mt7610u_stop();
	}
	if (sink)
		env->DeleteGlobalRef(sink);
}
