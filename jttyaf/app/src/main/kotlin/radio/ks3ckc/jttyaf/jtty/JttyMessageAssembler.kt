package radio.ks3ckc.jttyaf.jtty

/**
 * Chains accepted JTTY frames into messages (docs/jtty-protocol.md §4), the way WSJT-X
 * does on receive:
 *
 *  - A frame continues an open message when it starts `n` frame periods (n = 1..3,
 *    ±[CONTINUATION_TOLERANCE_S]) after that message's last frame and lies within
 *    `10 + 3·(n−1)` Hz of it. A skipped period (n > 1) inserts `...` in the text.
 *  - The EOM frame completes the message.
 *  - A message with no continuation for [FLUSH_PERIODS] frame periods is flushed as
 *    incomplete; at most [MAX_OPEN] messages are open at once (the oldest is flushed).
 *
 * Pure Kotlin so the rules are unit-tested without audio. Times are in samples at
 * [sampleRate]; the caller supplies the frame's start sample and the stream position
 * for [tick].
 */
class JttyMessageAssembler(
    private val sampleRate: Int = 12_000,
    private val sampleToUtcMillis: (Long) -> Long,
) {

    /** An in-progress or finished message plus the bookkeeping to extend it. */
    private class Open(
        var message: JttyMessage,
        /** Atoms joined but not yet trimmed/collapsed — TEXT5 padding spaces matter here. */
        var rawText: String,
        var lastStartSample: Long,
        var lastFreqHz: Float,
        var lastWasText5: Boolean,
    )

    private val open = ArrayList<Open>()
    private var nextId = 1L

    /** Frame period in samples (59 symbols × 384 samples at 12 kHz = 1.888 s). */
    private val framePeriod: Long = (FRAME_SECONDS * sampleRate).toLong()

    /**
     * Fold one accepted frame in. Returns the message it produced or extended (so the
     * caller can publish/replace that row), never null.
     */
    fun onFrame(frame: JttyCodec.DecodedFrame): JttyMessage {
        val text5 = isText5(frame.payload)
        val candidate = findContinuation(frame.startSample, frame.freqHz)
        if (candidate != null) {
            val gapPeriods = periodsBetween(candidate.lastStartSample, frame.startSample)
            val joiner = if (gapPeriods > 1) " ... " else joinerFor(candidate.lastWasText5, text5)
            val raw = candidate.rawText + joiner + frame.text
            val merged = candidate.message.copy(
                text = display(raw),
                snrDb = maxOf(candidate.message.snrDb, frame.snrDb),
                frames = candidate.message.frames + 1,
                complete = frame.eom,
            )
            candidate.message = merged
            candidate.rawText = raw
            candidate.lastStartSample = frame.startSample
            candidate.lastFreqHz = frame.freqHz
            candidate.lastWasText5 = text5
            if (frame.eom) open.remove(candidate)
            return merged
        }
        val fresh = JttyMessage(
            id = nextId++,
            utcMillis = sampleToUtcMillis(frame.startSample),
            freqHz = frame.freqHz,
            snrDb = frame.snrDb,
            text = display(frame.text),
            complete = frame.eom,
        )
        if (!frame.eom) {
            if (open.size >= MAX_OPEN) open.removeAt(0)
            open.add(Open(fresh, frame.text, frame.startSample, frame.freqHz, text5))
        }
        return fresh
    }

    /**
     * Advance the clock to [nowSample]: every open message whose last frame is more than
     * [FLUSH_PERIODS] periods old is closed as incomplete and returned so the caller can
     * mark its row.
     */
    fun tick(nowSample: Long): List<JttyMessage> {
        if (open.isEmpty()) return emptyList()
        val flushed = ArrayList<JttyMessage>()
        val it = open.iterator()
        while (it.hasNext()) {
            val o = it.next()
            if (nowSample - o.lastStartSample > (FLUSH_PERIODS + 1) * framePeriod) {
                val done = o.message.copy(complete = true, flushed = true)
                o.message = done
                flushed.add(done)
                it.remove()
            }
        }
        return flushed
    }

    /** Drop every open message (band change, operator Clear). */
    fun clear() {
        open.clear()
    }

    private fun findContinuation(startSample: Long, freqHz: Float): Open? {
        var best: Open? = null
        var bestFreqErr = Float.MAX_VALUE
        for (o in open) {
            val n = periodsBetween(o.lastStartSample, startSample)
            if (n < 1 || n > MAX_CONTINUATION_PERIODS) continue
            val expected = o.lastStartSample + n * framePeriod
            val timeErrS = Math.abs(startSample - expected) / sampleRate.toFloat()
            if (timeErrS > CONTINUATION_TOLERANCE_S) continue
            val freqErr = Math.abs(freqHz - o.lastFreqHz)
            if (freqErr > continuationFreqToleranceHz(n)) continue
            if (freqErr < bestFreqErr) {
                bestFreqErr = freqErr
                best = o
            }
        }
        return best
    }

    /** Whole frame periods between two frame starts, rounded to the nearest. */
    private fun periodsBetween(fromSample: Long, toSample: Long): Int =
        Math.round((toSample - fromSample).toDouble() / framePeriod).toInt()

    companion object {
        const val FRAME_SECONDS = 1.888
        const val MAX_CONTINUATION_PERIODS = 3
        const val FLUSH_PERIODS = 3
        const val MAX_OPEN = 30
        /** ±0.1 s around the expected continuation start. */
        const val CONTINUATION_TOLERANCE_S = 0.1f

        private val SPACE_RUN = Regex(" {2,}")

        /** `10 + 3·(n−1)` Hz: the frequency window widens with the gap. */
        fun continuationFreqToleranceHz(n: Int): Float = 10f + 3f * (n - 1)

        /**
         * Whether a 34-bit payload is a TEXT5 atom: the top-level type `i2` is the two
         * low bits of the 32-bit source word, which sits above the reserved and EOM bits
         * (docs/jtty-protocol.md §3), and `i2 = 3` is free text.
         */
        fun isText5(payload34: Long): Boolean = ((payload34 ushr 2) and 3L) == 3L

        /**
         * How two consecutive atoms join. TEXT5 atoms are 5-character slices of one piece
         * of free text and concatenate directly (the codec keeps their padding spaces, so
         * `HELLO` + ` WORL` + `D    ` reads back as `HELLO WORLD`); any pair involving a
         * structured atom (`CQ K1ABC CQ`, `599 001`) is separated by a single space.
         */
        fun joinerFor(previousWasText5: Boolean, nextIsText5: Boolean): String =
            if (previousWasText5 && nextIsText5) "" else " "

        /** The operator-facing form of the joined atoms: trimmed, padding runs collapsed. */
        fun display(raw: String): String = raw.trim().replace(SPACE_RUN, " ")
    }
}
