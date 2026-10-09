package radio.ks3ckc.jttyaf.jtty

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.timer.UtcTimer
import com.k1af.ft8af.wave.HamRecorder
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The continuous JTTY receive engine.
 *
 * Audio path: [attachToRecorder] registers a persistent (looping) tap on the
 * [HamRecorder] fan-out — the same mechanism SpectrumListener uses for the waterfall —
 * which delivers 12 kHz mono float buffers on the capture thread. [onAudioBuffer] copies
 * each buffer (the recorder reuses its array) into a bounded queue; ONE decode thread
 * drains the queue into the native streaming receiver, pops accepted frames, chains them
 * into messages ([JttyMessageAssembler]) and publishes [messages]. On overflow the OLDEST
 * buffer is dropped (live print cares about fresh audio) and the drop is logged.
 *
 * Received and transmitted text is also appended to `jtty_all.txt` in the app's external
 * files directory, WSJT-X ALL.TXT style.
 */
class JttySignalListener @JvmOverloads constructor(
    private val codec: JttyCodec,
    private val sampleRate: Int = SAMPLE_RATE_HZ,
    queueCapacity: Int = QUEUE_CAPACITY,
    private val log: (String) -> Unit = { GeneralVariables.fileLog(it) },
    private val clock: () -> Long = { UtcTimer.getSystemTime() },
    private val allTxtFile: () -> File? = {
        GeneralVariables.getMainContext()?.getExternalFilesDir(null)?.let { File(it, "jtty_all.txt") }
    },
) {

    private val mutableMessages = MutableLiveData<List<JttyMessage>>(emptyList())

    /** The receive text, oldest first, bounded to [MAX_MESSAGES] rows. */
    val messages: LiveData<List<JttyMessage>> get() = mutableMessages

    private val rows = ArrayList<JttyMessage>()
    private val rowsLock = Any()

    private val queue = ArrayBlockingQueue<FloatArray>(queueCapacity)
    private val enabled = AtomicBoolean(true)
    private val running = AtomicBoolean(false)
    private val dropped = AtomicLong(0)
    private val frameCount = AtomicInteger(0)

    /** Serializes every receiver call (decode thread vs settings changes). */
    private val receiverLock = Any()
    private var receiver: JttyCodec.Receiver? = null
    private var assembler: JttyMessageAssembler? = null

    /** Wall-clock millis that correspond to stream sample 0 (set on the first push). */
    private var streamEpochMillis = 0L
    private var epochSet = false

    private var decodeThread: Thread? = null

    /** The registered recorder tap; kept so [detachFromRecorder] can remove it. */
    private var tapRecorder: HamRecorder? = null
    private var tapMonitor: HamRecorder.VoiceDataMonitor? = null

    /** Last search settings pushed to the receiver, to skip redundant native calls. */
    private var appliedRxHz = Float.NaN
    private var appliedWide = false
    private var nextOwnId = -1L

    /** Recent own transmissions (text + end time) used to tag loopback decodes. */
    private val recentOwnTx = ArrayList<Pair<String, Long>>()

    /** Start the decode thread; safe to call once at app start. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        decodeThread = Thread({ decodeLoop() }, "JttyDecode").apply {
            isDaemon = true
            start()
        }
    }

    /** Stop the decode thread; the thread closes the receiver on its way out. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        detachFromRecorder()
        decodeThread?.interrupt()
        decodeThread = null
        queue.clear()
        // Direct-driven (test) mode has no thread to do the teardown.
        synchronized(receiverLock) {
            if (threadless) {
                receiver?.close()
                receiver = null
                threadless = false
            }
        }
    }

    /**
     * Register the persistent audio tap: a looping voice-data monitor that forwards every
     * [MONITOR_BUFFER_MS] chunk into the decode queue. Idempotent — re-attaching first
     * removes the previous tap, so two monitors can never double-feed the queue.
     */
    fun attachToRecorder(recorder: HamRecorder) {
        if (!recorder.isRunning) {
            log("JTTY RX: recorder not running; audio tap NOT attached")
            return
        }
        detachFromRecorder()
        val monitor = recorder.getVoiceData(MONITOR_BUFFER_MS, false) { data ->
            onAudioBuffer(data, data.size)
        }
        if (monitor == null) {
            log("JTTY RX: recorder stopped during attach; audio tap NOT attached")
            return
        }
        tapRecorder = recorder
        tapMonitor = monitor
    }

    /** Remove the audio tap from the recorder fan-out; no-op when detached. */
    fun detachFromRecorder() {
        val monitor = tapMonitor ?: return
        tapRecorder?.deleteVoiceDataMonitor(monitor)
        tapMonitor = null
        tapRecorder = null
    }

    /**
     * Feed one capture buffer (called on the recorder thread). Copies the data (the
     * recorder reuses its buffer) and enqueues it; on overflow the oldest queued buffer is
     * dropped and the drop is (rate-limited) logged.
     */
    fun onAudioBuffer(data: FloatArray, length: Int) {
        if (!enabled.get() || !running.get()) return
        val len = length.coerceAtMost(data.size)
        if (len <= 0) return
        val copy = data.copyOf(len)
        if (!queue.offer(copy)) {
            queue.poll() // drop-oldest: fresh audio wins
            val total = dropped.incrementAndGet()
            if (total == 1L || total % DROP_LOG_EVERY == 0L) {
                log("JTTY RX: audio queue overflow, dropped=$total buffers (decode thread lagging)")
            }
            queue.offer(copy)
        }
    }

    /** Enable/disable RX processing (default ON). Disabling clears the queue. */
    fun setEnabled(on: Boolean) {
        if (enabled.getAndSet(on) != on) {
            log("JTTY RX: listener ${if (on) "enabled" else "disabled"}")
        }
        if (!on) queue.clear()
    }

    fun isEnabled(): Boolean = enabled.get()

    /** Frames accepted since start (web status page). */
    fun getDecodedFrameCount(): Int = frameCount.get()

    /** Wipe the receive text (band change, operator Clear). Open messages are dropped too. */
    fun clearMessages() {
        synchronized(rowsLock) {
            rows.clear()
            publishLocked()
        }
        synchronized(receiverLock) { assembler?.clear() }
    }

    /**
     * Re-read the search settings (RX offset, wide-band toggle) and push them to the
     * receiver. Cheap when nothing changed; the decode loop also calls this every step so
     * a waterfall tap retargets the priority channel without any wiring.
     */
    fun applySearchSettings() {
        synchronized(receiverLock) {
            val rx = receiver ?: return
            val hz = GeneralVariables.getBaseFrequency()
            val wide = GeneralVariables.jttyWideSearch
            if (hz == appliedRxHz && wide == appliedWide) return
            appliedRxHz = hz
            appliedWide = wide
            if (wide) {
                rx.setSearchBand(WIDE_LO_HZ, WIDE_HI_HZ)
            } else {
                rx.setSearchBand(hz - NARROW_HALF_HZ, hz + NARROW_HALF_HZ)
            }
            rx.setRxFrequency(hz, RX_TOLERANCE_HZ)
        }
    }

    /**
     * Our transmitter just finished sending [text]: echo it into the receive pane (when
     * enabled) and remember it so a loopback decode of our own signal over the next few
     * seconds is tagged as ours rather than shown as another station.
     */
    fun onOwnTransmission(text: String) {
        val now = clock()
        synchronized(recentOwnTx) {
            recentOwnTx.add(text.trim().uppercase() to now)
            while (recentOwnTx.size > 8) recentOwnTx.removeAt(0)
        }
        appendAllTxt("TX", GeneralVariables.getBaseFrequency(), text, now)
        if (!GeneralVariables.jttyEchoOwnTx) return
        val row = JttyMessage(
            id = nextOwnId--,
            utcMillis = now,
            freqHz = GeneralVariables.getBaseFrequency(),
            snrDb = 0f,
            text = text.trim(),
            complete = true,
            isOwnTx = true,
        )
        synchronized(rowsLock) {
            rows.add(row)
            trimLocked()
            publishLocked()
        }
    }

    // ------------------------------------------------------------------
    // Decode thread
    // ------------------------------------------------------------------

    private fun decodeLoop() {
        val rx = try {
            codec.newReceiver(sampleRate)
        } catch (t: Throwable) {
            // No native lib (JVM tests / unsupported ABI): shut down cleanly rather than
            // crash-looping — the app runs fine without RX.
            log("JTTY RX: receiver unavailable (${t.javaClass.simpleName}: ${t.message}); listener stopped")
            running.set(false)
            return
        }
        synchronized(receiverLock) {
            receiver = rx
            assembler = JttyMessageAssembler(sampleRate) { sample -> sampleToUtc(sample) }
        }
        applySearchSettings()
        log("JTTY RX: listening (rate=$sampleRate Hz)")
        try {
            while (running.get()) {
                try {
                    stepOnce(POLL_WAIT_MS)
                } catch (ie: InterruptedException) {
                    // stop() interrupts; the loop condition decides.
                } catch (t: Throwable) {
                    log("JTTY RX: decode step failed: $t")
                }
            }
        } finally {
            synchronized(receiverLock) {
                receiver = null
                assembler = null
            }
            rx.close()
        }
    }

    /**
     * One decode-loop iteration: wait up to [waitMs] for audio, push everything queued
     * into the receiver, pop and publish any accepted frames, then age out open messages.
     * Package-internal so tests can drive the loop synchronously.
     */
    internal fun stepOnce(waitMs: Long) {
        val first = if (waitMs > 0) queue.poll(waitMs, TimeUnit.MILLISECONDS) else queue.poll()
        synchronized(receiverLock) {
            val rx = receiver ?: return
            val asm = assembler ?: return
            applySearchSettings()
            var buf = first
            var pending = 0
            while (buf != null) {
                if (!epochSet) {
                    // Stream sample 0 is "now minus the buffer we are about to push".
                    streamEpochMillis = clock() - buf.size * 1000L / sampleRate
                    epochSet = true
                }
                pending = rx.push(buf, buf.size)
                buf = queue.poll()
            }
            if (pending > 0) {
                val frame = JttyCodec.DecodedFrame()
                while (rx.pop(frame)) {
                    frameCount.incrementAndGet()
                    onDecodedFrame(asm.onFrame(frame))
                }
            }
            for (flushed in asm.tick(rx.samplesConsumed())) {
                onDecodedFrame(flushed)
            }
        }
    }

    private fun sampleToUtc(sample: Long): Long = streamEpochMillis + sample * 1000L / sampleRate

    /** Publish a new or extended message row (replacing the row with the same id). */
    private fun onDecodedFrame(message: JttyMessage) {
        val tagged = if (isOwnLoopback(message)) message.copy(isOwnTx = true) else message
        synchronized(rowsLock) {
            val idx = rows.indexOfLast { it.id == tagged.id }
            if (idx >= 0) rows[idx] = tagged else rows.add(tagged)
            trimLocked()
            publishLocked()
        }
        if (tagged.complete) {
            log("JTTY RX: ${tagged.displayLine()}${if (tagged.flushed) " (incomplete)" else ""}")
            if (!tagged.isOwnTx) appendAllTxt("RX", tagged.freqHz, tagged.text, tagged.utcMillis, tagged.snrDb)
        }
    }

    /**
     * Whether a decode is the rig monitoring our own transmission back to us: same text
     * as something we sent within the last [OWN_ECHO_WINDOW_MS]. A frame-level prefix
     * match is enough because a multi-frame echo grows row by row.
     */
    private fun isOwnLoopback(message: JttyMessage): Boolean {
        val text = message.text.trim().uppercase()
        if (text.isEmpty()) return false
        val now = clock()
        synchronized(recentOwnTx) {
            recentOwnTx.removeAll { now - it.second > OWN_ECHO_WINDOW_MS }
            return recentOwnTx.any { (sent, _) -> sent == text || sent.startsWith(text) }
        }
    }

    private fun trimLocked() {
        while (rows.size > MAX_MESSAGES) rows.removeAt(0)
    }

    private fun publishLocked() {
        // A fresh list instance so Compose's structural comparison sees the change.
        mutableMessages.postValue(ArrayList(rows))
    }

    private fun appendAllTxt(dir: String, freqHz: Float, text: String, utcMillis: Long, snrDb: Float = 0f) {
        try {
            val f = allTxtFile() ?: return
            val line = String.format(
                java.util.Locale.US, "%s %s %5.0f %s %s\n",
                UtcTimer.getDatetimeYYYYMMDD_HHMMSS(utcMillis), dir, freqHz,
                if (dir == "RX") String.format(java.util.Locale.US, "%+3.0f", snrDb) else "   ",
                text,
            )
            f.appendText(line)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Test hooks (JVM tests drive the loop synchronously — no decode thread)
    // ------------------------------------------------------------------

    private var threadless = false

    /** Test-only start: create the receiver on the caller thread, no thread. */
    internal fun startDirect() {
        check(running.compareAndSet(false, true)) { "already running" }
        threadless = true
        synchronized(receiverLock) {
            receiver = codec.newReceiver(sampleRate)
            assembler = JttyMessageAssembler(sampleRate) { sample -> sampleToUtc(sample) }
        }
    }

    internal fun droppedBufferCount(): Long = dropped.get()

    internal fun queuedBufferCount(): Int = queue.size

    internal fun snapshot(): List<JttyMessage> = synchronized(rowsLock) { ArrayList(rows) }

    companion object {
        /** The recorder chain is fixed 12 kHz mono float (HamRecorder). */
        const val SAMPLE_RATE_HZ = 12_000

        /** Looping voice-monitor chunk size, ms (~2400 samples at 12 kHz). */
        internal const val MONITOR_BUFFER_MS = 200

        /** ~13 s of audio headroom before drops start. */
        internal const val QUEUE_CAPACITY = 64

        /** Decode-thread wait per iteration. */
        internal const val POLL_WAIT_MS = 250L

        /** Rate limit for the overflow log line. */
        internal const val DROP_LOG_EVERY = 50L

        /** Rows kept in the receive pane. */
        internal const val MAX_MESSAGES = 500

        /** Wide-band search limits for the lowest tone. */
        internal const val WIDE_LO_HZ = 300f
        internal const val WIDE_HI_HZ = 2700f

        /** Narrow (RX-offset only) search half-width. */
        internal const val NARROW_HALF_HZ = 150f

        /** Priority-channel tolerance around the RX offset. */
        internal const val RX_TOLERANCE_HZ = 50f

        /** How long after an over a matching decode is still treated as our loopback. */
        internal const val OWN_ECHO_WINDOW_MS = 40_000L
    }
}
