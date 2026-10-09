package radio.ks3ckc.jttyaf.jtty

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.k1af.ft8af.GeneralVariables
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JTTY keyboard transmitter: text → frames → GFSK audio → key PTT → play → unkey, on
 * a worker thread, composing the extracted transmit plumbing the same way TuneOperator
 * does (PttController keyDown/keyUp around a blocking TransmitAudioSink play, un-key in a
 * finally — a stuck carrier is never acceptable).
 *
 * JTTY is asynchronous: an over starts the moment the operator sends it and lasts
 * `frames × 1.888 s`. There is no slot, no late-start clipping, no auto-sequence.
 *
 * The controller/sink are injected behind the minimal [Keyer]/[Player] interfaces so JVM
 * tests can fake them; MainViewModel adapts the real PttController + TransmitAudioSink.
 */
class JttyTransmitter @JvmOverloads constructor(
    private val codec: JttyCodec,
    private val keyer: Keyer,
    private val player: Player,
    private val tuneActive: TuneActiveCheck = TuneActiveCheck { false },
    private val sampleRateSource: () -> Int = { GeneralVariables.audioSampleRate },
    private val f0Source: () -> Float = { GeneralVariables.getBaseFrequency() },
    private val profileSource: () -> Int = { GeneralVariables.jttyProfile },
    private val settleDelayMsSource: () -> Long = { GeneralVariables.pttDelay.toLong() },
    private val log: (String) -> Unit = { GeneralVariables.fileLog(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Runs the TX worker; tests substitute a synchronous runner. */
    private val workerRunner: (Runnable) -> Unit = { body -> Thread(body, "JttyTransmit").start() },
) {

    /** Rig keying surface (MainViewModel adapts PttController). */
    interface Keyer {
        fun keyDown()

        fun keyUp()
    }

    /** Audio playback surface (MainViewModel adapts TransmitAudioSink). */
    interface Player {
        /** Blocking whole-buffer play. True when it played to completion. */
        fun play(buffer: FloatArray, sampleRate: Int): Boolean

        /** Abort an in-progress play from any thread (unblocks [play]). */
        fun cancel()
    }

    /** Whether the Tune carrier currently owns the rig. */
    fun interface TuneActiveCheck {
        fun isActive(): Boolean
    }

    /** Why [send] refused a message. */
    enum class Reject { NONE, EMPTY, TOO_LONG, UNPACKABLE, BUSY, TUNE_ACTIVE }

    private val transmitting = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    private val mutableIsTransmitting = MutableLiveData(false)
    val isTransmitting: LiveData<Boolean> get() = mutableIsTransmitting

    private val mutableTxProgress = MutableLiveData(0f)
    val txProgress: LiveData<Float> get() = mutableTxProgress

    private val mutableCurrentText = MutableLiveData<String?>(null)
    /** The text on the air right now (null when idle). */
    val currentText: LiveData<String?> get() = mutableCurrentText

    private var onTransmitted: ((String) -> Unit)? = null
    private var onTxEnd: Runnable? = null

    /** Called (worker thread) with the text once an over has played to completion. */
    fun setOnTransmitted(cb: ((String) -> Unit)?) {
        onTransmitted = cb
    }

    /** Called (worker thread) after every over, completed or not. */
    fun setOnTxEnd(cb: Runnable?) {
        onTxEnd = cb
    }

    /**
     * Validate [text] without transmitting: how it would pack, or why it cannot be sent.
     * Used by the operate screen to grey out Send and show the frame count.
     */
    fun check(text: String): Pair<Reject, Int> {
        val t = normalize(text)
        if (t.isEmpty()) return Reject.EMPTY to 0
        if (t.length > MAX_TEXT) return Reject.TOO_LONG to 0
        val frames = try {
            codec.packFrames(t, profileSource())
        } catch (_: Throwable) {
            -1
        }
        if (frames <= 0) return Reject.UNPACKABLE to 0
        return Reject.NONE to frames
    }

    /**
     * Start transmitting [text]. Returns [Reject.NONE] and keys the rig on a worker
     * thread, or the reason it was refused without keying.
     */
    fun send(text: String): Reject {
        val (reject, _) = check(text)
        if (reject != Reject.NONE) return reject
        if (tuneActive.isActive()) {
            log("JTTY TX: rejected — tune carrier active")
            return Reject.TUNE_ACTIVE
        }
        if (!transmitting.compareAndSet(false, true)) {
            log("JTTY TX: rejected — already transmitting")
            return Reject.BUSY
        }
        cancelled.set(false)
        mutableIsTransmitting.postValue(true)
        mutableTxProgress.postValue(0f)
        val normalized = normalize(text)
        mutableCurrentText.postValue(normalized)
        workerRunner(Runnable { runTransmission(normalized) })
        return Reject.NONE
    }

    /** Abort an in-flight transmission; the worker's finally un-keys PTT. */
    fun cancel() {
        if (!transmitting.get()) return
        cancelled.set(true)
        player.cancel()
    }

    fun isTransmittingNow(): Boolean = transmitting.get()

    // ------------------------------------------------------------------

    private fun runTransmission(text: String) {
        var keyed = false
        var completed = false
        val startedAt = clock()
        try {
            val sampleRate = sampleRateSource()
            val f0 = f0Source()
            // Encode BEFORE keying: a bad message must never key the rig.
            val audio = codec.encode(text, profileSource(), f0, sampleRate, NativeJttyCodec.ENCODE_AMPLITUDE)
            val durationMs = audio.size * 1000L / sampleRate
            log("JTTY TX: start — \"$text\" f0=${f0}Hz samples=${audio.size} rate=$sampleRate durationMs=$durationMs")

            // A cancel that lands during encode would otherwise be lost: the sink clears its
            // own cancelled flag on play() entry, so gate keying/playback on our own flag.
            if (cancelled.get()) {
                log("JTTY TX: cancelled before keying")
                return
            }

            keyer.keyDown()
            keyed = true
            val settleMs = settleDelayMsSource()
            if (settleMs > 0) Thread.sleep(settleMs)

            val ticker = ProgressTicker(durationMs)
            try {
                if (cancelled.get()) {
                    log("JTTY TX: cancelled during PTT settle")
                } else {
                    completed = player.play(audio, sampleRate)
                }
            } finally {
                // Stop-and-join so a late ticker post can never overwrite the final
                // progress value published below.
                ticker.stop()
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            log("JTTY TX: interrupted")
        } catch (t: Throwable) {
            log("JTTY TX: failed — ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            // Single point of teardown: whatever happened above, PTT drops.
            if (keyed) {
                try {
                    keyer.keyUp()
                } catch (t: Throwable) {
                    log("JTTY TX: key-up failed — $t")
                }
            }
            mutableTxProgress.postValue(if (completed) 1f else 0f)
            mutableCurrentText.postValue(null)
            transmitting.set(false)
            mutableIsTransmitting.postValue(false)
            log("JTTY TX: end — completed=$completed cancelled=${cancelled.get()} elapsedMs=${clock() - startedAt}")
            if (completed) {
                try {
                    onTransmitted?.invoke(text)
                } catch (t: Throwable) {
                    log("JTTY TX: onTransmitted threw — $t")
                }
            }
            try {
                onTxEnd?.run()
            } catch (t: Throwable) {
                log("JTTY TX: onTxEnd threw — $t")
            }
        }
    }

    /** Elapsed-time progress publisher (the sink has no playback-progress callback). */
    private inner class ProgressTicker(durationMs: Long) {
        private val keepRunning = AtomicBoolean(true)
        private val startedAt = clock()
        private val thread = Thread(
            {
                while (keepRunning.get()) {
                    mutableTxProgress.postValue(txProgressFraction(clock() - startedAt, durationMs))
                    try {
                        Thread.sleep(PROGRESS_TICK_MS)
                    } catch (ie: InterruptedException) {
                        return@Thread
                    }
                }
            },
            "JttyTxProgress",
        ).apply {
            isDaemon = true
            start()
        }

        /** Deterministic stop: no posts can land after this returns. */
        fun stop() {
            keepRunning.set(false)
            thread.interrupt()
            var interrupted = false
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (ie: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** JTTY rejects (does not truncate) text longer than this. */
        const val MAX_TEXT = 80

        internal const val PROGRESS_TICK_MS = 200L
        internal const val MAX_TICKER_PROGRESS = 0.99f

        /** Upper-case, trim, collapse repeated spaces — what the codec does before packing. */
        fun normalize(text: String): String =
            text.trim().replace(Regex("\\s+"), " ").uppercase(java.util.Locale.US)

        /**
         * Elapsed/duration as a 0..1 fraction, capped just below 1 — only a completed play
         * posts exactly 1.0. Pure; unit-tested directly.
         */
        @JvmStatic
        internal fun txProgressFraction(elapsedMs: Long, durationMs: Long): Float {
            if (durationMs <= 0) return 0f
            return (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, MAX_TICKER_PROGRESS)
        }
    }
}
