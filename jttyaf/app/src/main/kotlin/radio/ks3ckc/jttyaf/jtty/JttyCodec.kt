package radio.ks3ckc.jttyaf.jtty

/**
 * The JTTY codec surface the engine talks to. [NativeJttyCodec] is the JNI binding to
 * `cpp/jtty_lib`; JVM tests substitute fakes.
 */
interface JttyCodec {

    /**
     * How many frames [text] packs into under [profile], or a negative `JTTY_ERR_*`
     * (empty text, more than 80 characters). Pure — no audio is generated.
     */
    fun packFrames(text: String, profile: Int): Int

    /**
     * Pack, encode and modulate [text]: the complete transmission as a mono float
     * waveform at [sampleRate] with the lowest tone at [f0Hz] and peak [amplitude].
     * Throws [IllegalArgumentException] when the text cannot be sent.
     */
    fun encode(text: String, profile: Int, f0Hz: Float, sampleRate: Int, amplitude: Float): FloatArray

    /** A fresh streaming receiver for 12 kHz mono float audio. */
    fun newReceiver(sampleRate: Int): Receiver

    /** One accepted frame popped from a [Receiver]; a mutable holder the JNI side fills. */
    class DecodedFrame {
        var payload: Long = 0
        var eom: Boolean = false
        var text: String = ""
        var freqHz: Float = 0f
        var startSample: Long = 0
        var dtSec: Float = 0f
        var snrDb: Float = 0f
        var syncScore: Float = 0f
        var listRank: Int = 0
        var pass: Int = 0
    }

    /**
     * Streaming push receiver. Not thread-safe: the listener serializes every call.
     * [close] is idempotent.
     */
    interface Receiver : AutoCloseable {
        /** Feed audio; returns how many new frames are waiting in the queue. */
        fun push(samples: FloatArray, length: Int): Int

        /** Pop the next accepted frame (time order) into [out]; false when empty. */
        fun pop(out: DecodedFrame): Boolean

        /** Lowest-tone search band for the wide-band search. */
        fun setSearchBand(loHz: Float, hiHz: Float)

        /** The operator's RX offset (priority channel) and its tolerance; tol <= 0 disables. */
        fun setRxFrequency(hz: Float, tolHz: Float)

        /** Cap on candidate decode attempts per analysis step (0 = library default). */
        fun setDecodeBudget(maxCandidatesPerStep: Int)

        /** Forget buffered audio and candidates; keeps the configuration. */
        fun reset()

        /** Samples consumed since create/reset, for mapping frame times to the clock. */
        fun samplesConsumed(): Long
    }
}
