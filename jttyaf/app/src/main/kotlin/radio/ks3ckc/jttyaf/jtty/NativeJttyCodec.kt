package radio.ks3ckc.jttyaf.jtty

import android.util.Log

/**
 * The real [JttyCodec]: JNI bridge to the clean-room codec in `cpp/jtty_lib`, glued in
 * `cpp/jttyaf_glue/jtty_jni.cpp`.
 *
 * The native symbols are bound to this exact class
 * (`Java_radio_ks3ckc_jttyaf_jtty_NativeJttyCodec_*`), and `nativeRxPop` fills
 * [JttyCodec.DecodedFrame]'s fields by name, so neither the class names nor the
 * `external` method names / frame field names may change (see proguard-rules.pro).
 *
 * Library load follows the UsbAudioNative pattern: a JVM unit test has no libjttyaf.so,
 * so the loader failure is caught and remembered — the native methods then throw
 * [UnsatisfiedLinkError] only if actually invoked (the engine's decode thread catches
 * that and shuts itself down cleanly).
 */
class NativeJttyCodec : JttyCodec {

    override fun packFrames(text: String, profile: Int): Int = nativePackFrames(text, profile)

    override fun encode(
        text: String,
        profile: Int,
        f0Hz: Float,
        sampleRate: Int,
        amplitude: Float,
    ): FloatArray {
        // status[0] = frames packed (or the negative JTTY_ERR_* code on failure)
        val status = IntArray(1)
        val out = nativeEncode(text, profile, f0Hz, sampleRate, amplitude, status)
        require(out != null && status[0] > 0) {
            "jtty_encode_text failed (code ${status[0]}) for \"$text\""
        }
        return out
    }

    override fun newReceiver(sampleRate: Int): JttyCodec.Receiver {
        val handle = nativeRxCreate(sampleRate)
        check(handle != 0L) { "jtty_rx_create($sampleRate) failed" }
        return NativeReceiver(handle)
    }

    /**
     * Wraps one native receiver handle. All methods synchronize on the receiver so the
     * not-thread-safe native state never sees concurrent calls, and [close] is idempotent
     * (handle zeroed under the same lock).
     */
    private inner class NativeReceiver(private var handle: Long) : JttyCodec.Receiver {

        private fun requireHandle(): Long {
            val h = handle
            check(h != 0L) { "receiver is closed" }
            return h
        }

        @Synchronized
        override fun push(samples: FloatArray, length: Int): Int =
            nativeRxPush(requireHandle(), samples, length.coerceAtMost(samples.size))

        @Synchronized
        override fun pop(out: JttyCodec.DecodedFrame): Boolean = nativeRxPop(requireHandle(), out)

        @Synchronized
        override fun setSearchBand(loHz: Float, hiHz: Float) {
            nativeRxSetSearchBand(requireHandle(), loHz, hiHz)
        }

        @Synchronized
        override fun setRxFrequency(hz: Float, tolHz: Float) {
            nativeRxSetRxFrequency(requireHandle(), hz, tolHz)
        }

        @Synchronized
        override fun setDecodeBudget(maxCandidatesPerStep: Int) {
            nativeRxSetDecodeBudget(requireHandle(), maxCandidatesPerStep)
        }

        @Synchronized
        override fun reset() {
            nativeRxReset(requireHandle())
        }

        @Synchronized
        override fun samplesConsumed(): Long = nativeRxSamplesConsumed(requireHandle())

        @Synchronized
        override fun close() {
            val h = handle
            if (h != 0L) {
                handle = 0L
                nativeRxDestroy(h)
            }
        }
    }

    // ----- JNI surface (cpp/jttyaf_glue/jtty_jni.cpp) -----

    private external fun nativePackFrames(text: String, profile: Int): Int

    @Suppress("LongParameterList")
    private external fun nativeEncode(
        text: String,
        profile: Int,
        f0Hz: Float,
        sampleRate: Int,
        amplitude: Float,
        status: IntArray,
    ): FloatArray?

    private external fun nativeRxCreate(sampleRate: Int): Long

    private external fun nativeRxDestroy(handle: Long)

    private external fun nativeRxReset(handle: Long)

    private external fun nativeRxSetSearchBand(handle: Long, loHz: Float, hiHz: Float)

    private external fun nativeRxSetRxFrequency(handle: Long, hz: Float, tolHz: Float)

    private external fun nativeRxSetDecodeBudget(handle: Long, maxCandidates: Int)

    private external fun nativeRxPush(handle: Long, samples: FloatArray, n: Int): Int

    private external fun nativeRxPop(handle: Long, out: JttyCodec.DecodedFrame): Boolean

    private external fun nativeRxSamplesConsumed(handle: Long): Long

    companion object {
        private const val TAG = "NativeJttyCodec"

        /**
         * Encoder output amplitude. Slightly below full scale so the downstream
         * float→int16 conversion (TransmitAudioSink) never sits on the clip boundary; the
         * TX volume is applied later in the sink.
         */
        const val ENCODE_AMPLITUDE = 0.95f

        init {
            try {
                System.loadLibrary("jttyaf")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Failed to load libjttyaf: ${e.message}")
            }
        }
    }
}
