// JTTY codec JNI wrappers — bind the clean-room codec (jtty_lib/jtty.h) to Kotlin.
//
// Java side:
//   radio.ks3ckc.jttyaf.jtty.NativeJttyCodec (instance methods)
//   radio.ks3ckc.jttyaf.jtty.JttyCodec$DecodedFrame (fields filled by nativeRxPop)
//
// Handle model (same pattern the SSTVAF decoder glue uses): create returns an opaque
// jtty_rx_t* packed into a jlong; every other entry point unpacks it. The Kotlin
// NativeReceiver wrapper serializes access and guarantees destroy is called exactly once —
// the receiver itself is not thread-safe and these wrappers add no locking.

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "../jtty_lib/jtty.h"

static inline jtty_rx_t* rx_from_handle(jlong handle)
{
    return (jtty_rx_t*)(intptr_t)handle;
}

// jtty_pack_text(text, profile) -> frame count or JTTY_ERR_*.
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_nativePackFrames(
        JNIEnv* env, jobject, jstring text, jint profile)
{
    if (text == nullptr) {
        return JTTY_ERR_BAD_ARGS;
    }
    const char* utf = env->GetStringUTFChars(text, nullptr);
    if (utf == nullptr) {
        return JTTY_ERR_NOMEM;
    }
    uint64_t payloads[JTTY_MAX_FRAMES];
    int n = jtty_pack_text(utf, (jtty_profile_t)profile, payloads, JTTY_MAX_FRAMES);
    env->ReleaseStringUTFChars(text, utf);
    return n;
}

// jtty_encode_text(...) -> float[] waveform, or null on failure. status[0] receives the
// frame count (>0) or the negative JTTY_ERR_* code so Kotlin can report why.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_nativeEncode(
        JNIEnv* env, jobject, jstring text, jint profile, jfloat f0Hz, jint sampleRate,
        jfloat amplitude, jintArray status)
{
    jint code = JTTY_ERR_BAD_ARGS;
    jfloatArray result = nullptr;
    if (text != nullptr && sampleRate > 0) {
        const char* utf = env->GetStringUTFChars(text, nullptr);
        if (utf == nullptr) {
            code = JTTY_ERR_NOMEM;
        } else {
            uint64_t payloads[JTTY_MAX_FRAMES];
            int n_frames = jtty_pack_text(utf, (jtty_profile_t)profile, payloads, JTTY_MAX_FRAMES);
            if (n_frames <= 0) {
                code = n_frames;
            } else {
                int n_samples = jtty_waveform_samples(n_frames, sampleRate);
                float* buf = n_samples > 0 ? (float*)malloc((size_t)n_samples * sizeof(float)) : nullptr;
                if (buf == nullptr) {
                    code = JTTY_ERR_NOMEM;
                } else {
                    int written = jtty_encode_text(utf, (jtty_profile_t)profile, f0Hz, sampleRate,
                                                   amplitude, buf, n_samples, &n_frames);
                    if (written <= 0) {
                        code = written;
                    } else {
                        result = env->NewFloatArray(written);
                        if (result != nullptr) {
                            env->SetFloatArrayRegion(result, 0, written, buf);
                            code = n_frames;
                        } else {
                            code = JTTY_ERR_NOMEM;
                        }
                    }
                    free(buf);
                }
            }
            env->ReleaseStringUTFChars(text, utf);
        }
    }
    if (status != nullptr && env->GetArrayLength(status) >= 1) {
        env->SetIntArrayRegion(status, 0, 1, &code);
    }
    return result;
}

// jtty_rx_create(sample_rate) -> handle, 0 on failure (bad rate / OOM).
extern "C" JNIEXPORT jlong JNICALL
Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_nativeRxCreate(JNIEnv*, jobject, jint sampleRate)
{
    return (jlong)(intptr_t)jtty_rx_create(sampleRate);
}

extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_nativeRxDestroy(JNIEnv*, jobject, jlong handle)
{
    jtty_rx_t* rx = rx_from_handle(handle);
    if (rx != nullptr) {
        jtty_rx_destroy(rx);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_nativeRxReset(JNIEnv*, jobject, jlong handle)
{
    jtty_rx_t* rx = rx_from_handle(handle);
    if (rx != nullptr) {
        jtty_rx_reset(rx);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_nativeRxSetSearchBand(
        JNIEnv*, jobject, jlong handle, jfloat loHz, jfloat hiHz)
{
    jtty_rx_t* rx = rx_from_handle(handle);
    if (rx != nullptr) {
        jtty_rx_set_search_band(rx, loHz, hiHz);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_nativeRxSetRxFrequency(
        JNIEnv*, jobject, jlong handle, jfloat hz, jfloat tolHz)
{
    jtty_rx_t* rx = rx_from_handle(handle);
    if (rx != nullptr) {
        jtty_rx_set_rx_frequency(rx, hz, tolHz);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_nativeRxSetDecodeBudget(
        JNIEnv*, jobject, jlong handle, jint maxCandidates)
{
    jtty_rx_t* rx = rx_from_handle(handle);
    if (rx != nullptr) {
        jtty_rx_set_decode_budget(rx, maxCandidates);
    }
}

// jtty_rx_push(rx, samples, n) -> frames now queued, or JTTY_ERR_BAD_ARGS.
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_nativeRxPush(
        JNIEnv* env, jobject, jlong handle, jfloatArray samples, jint n)
{
    jtty_rx_t* rx = rx_from_handle(handle);
    if (rx == nullptr || samples == nullptr || n <= 0) {
        return JTTY_ERR_BAD_ARGS;
    }
    jsize len = env->GetArrayLength(samples);
    if (n > len) {
        n = len;
    }
    jfloat* buf = env->GetFloatArrayElements(samples, nullptr);
    if (buf == nullptr) {
        return JTTY_ERR_NOMEM;
    }
    int queued = jtty_rx_push(rx, buf, n);
    env->ReleaseFloatArrayElements(samples, buf, JNI_ABORT);
    return queued;
}

// jtty_rx_pop(rx, &frame) -> fill the DecodedFrame fields; false when the queue is empty.
// Field names are looked up by string, so JttyCodec.DecodedFrame's fields are kept by
// name in proguard-rules.pro.
extern "C" JNIEXPORT jboolean JNICALL
Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_nativeRxPop(
        JNIEnv* env, jobject, jlong handle, jobject out)
{
    jtty_rx_t* rx = rx_from_handle(handle);
    if (rx == nullptr || out == nullptr) {
        return JNI_FALSE;
    }
    jtty_decode_t d;
    memset(&d, 0, sizeof d);
    if (!jtty_rx_pop(rx, &d)) {
        return JNI_FALSE;
    }
    jclass cls = env->GetObjectClass(out);
    if (cls == nullptr) {
        return JNI_FALSE;
    }
    jfieldID fPayload = env->GetFieldID(cls, "payload", "J");
    jfieldID fEom = env->GetFieldID(cls, "eom", "Z");
    jfieldID fText = env->GetFieldID(cls, "text", "Ljava/lang/String;");
    jfieldID fFreq = env->GetFieldID(cls, "freqHz", "F");
    jfieldID fStart = env->GetFieldID(cls, "startSample", "J");
    jfieldID fDt = env->GetFieldID(cls, "dtSec", "F");
    jfieldID fSnr = env->GetFieldID(cls, "snrDb", "F");
    jfieldID fSync = env->GetFieldID(cls, "syncScore", "F");
    jfieldID fRank = env->GetFieldID(cls, "listRank", "I");
    jfieldID fPass = env->GetFieldID(cls, "pass", "I");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    env->SetLongField(out, fPayload, (jlong)d.payload34);
    env->SetBooleanField(out, fEom, d.eom ? JNI_TRUE : JNI_FALSE);
    jstring text = env->NewStringUTF(d.text);
    if (text != nullptr) {
        env->SetObjectField(out, fText, text);
        env->DeleteLocalRef(text);
    }
    env->SetFloatField(out, fFreq, d.freq_hz);
    env->SetLongField(out, fStart, (jlong)d.start_sample);
    env->SetFloatField(out, fDt, d.dt_s);
    env->SetFloatField(out, fSnr, d.snr_db);
    env->SetFloatField(out, fSync, d.sync_score);
    env->SetIntField(out, fRank, d.list_rank);
    env->SetIntField(out, fPass, d.pass);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_nativeRxSamplesConsumed(JNIEnv*, jobject, jlong handle)
{
    jtty_rx_t* rx = rx_from_handle(handle);
    return rx != nullptr ? (jlong)jtty_rx_samples_consumed(rx) : 0;
}
