package radio.ks3ckc.jttyaf.jtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The frame-to-message chaining rules (docs/jtty-protocol.md §4) without any audio:
 * continuation within n frame periods and 10+3(n-1) Hz, `...` on a skipped period,
 * EOM completion, flush after three silent periods, and the join rules between atoms.
 */
class JttyMessageAssemblerTest {

    private val period = (JttyMessageAssembler.FRAME_SECONDS * 12_000).toLong()

    /** A structured atom by default; [text5] marks the payload as free text (`i2 = 3`). */
    private fun frame(
        start: Long,
        text: String,
        eom: Boolean,
        freq: Float = 1500f,
        snr: Float = -5f,
        text5: Boolean = false,
    ) = JttyCodec.DecodedFrame().apply {
        startSample = start
        this.text = text
        this.eom = eom
        freqHz = freq
        snrDb = snr
        payload = (if (text5) TEXT5_PAYLOAD else CALL_PAYLOAD) or (if (eom) 1L else 0L)
    }

    private companion object {
        /** `CQ K1ABC CQ`, EOM clear (docs/jtty-protocol.md §3.4). */
        const val CALL_PAYLOAD = 0x026F78D40L
        /** TEXT5 `HELLO`, EOM clear. */
        const val TEXT5_PAYLOAD = 0x11395558CL
    }

    private fun assembler() = JttyMessageAssembler(12_000) { s -> s * 1000L / 12_000 }

    @Test
    fun singleFrameWithEomIsCompleteMessage() {
        val m = assembler().onFrame(frame(0, "CQ K1ABC CQ", eom = true))
        assertThat(m.text).isEqualTo("CQ K1ABC CQ")
        assertThat(m.complete).isTrue()
        assertThat(m.frames).isEqualTo(1)
        assertThat(m.freqHz).isEqualTo(1500f)
    }

    @Test
    fun consecutiveFramesChainIntoOneMessage() {
        val a = assembler()
        val first = a.onFrame(frame(1000, "W9XYZ", eom = false))
        val second = a.onFrame(frame(1000 + period + 12, "599 107", eom = true, freq = 1503f, snr = -2f))
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.text).isEqualTo("W9XYZ 599 107")
        assertThat(second.complete).isTrue()
        assertThat(second.frames).isEqualTo(2)
        // Strongest frame wins the reported S/N.
        assertThat(second.snrDb).isEqualTo(-2f)
    }

    @Test
    fun text5RunsConcatenateWithoutExtraSpaces() {
        val a = assembler()
        a.onFrame(frame(0, "HELLO", eom = false, text5 = true))
        val m = a.onFrame(frame(period, " WORL", eom = false, text5 = true))
        val n = a.onFrame(frame(2 * period, "D    ", eom = true, text5 = true))
        assertThat(m.text).isEqualTo("HELLO WORL")
        assertThat(n.text).isEqualTo("HELLO WORLD")
    }

    @Test
    fun structuredAtomAfterPaddedTextGetsOneSpace() {
        val a = assembler()
        a.onFrame(frame(0, "HI   ", eom = false, text5 = true))
        val m = a.onFrame(frame(period, "K1ABC", eom = true))
        assertThat(m.text).isEqualTo("HI K1ABC")
    }

    @Test
    fun skippedPeriodInsertsEllipsis() {
        val a = assembler()
        a.onFrame(frame(0, "CQ K1ABC CQ", eom = false))
        val m = a.onFrame(frame(2 * period, "FN42", eom = true))
        assertThat(m.text).isEqualTo("CQ K1ABC CQ ... FN42")
    }

    @Test
    fun frameOutsideFrequencyWindowStartsNewMessage() {
        val a = assembler()
        val first = a.onFrame(frame(0, "W9XYZ", eom = false, freq = 1500f))
        val other = a.onFrame(frame(period, "599 001", eom = true, freq = 1530f))
        assertThat(other.id).isNotEqualTo(first.id)
        assertThat(other.text).isEqualTo("599 001")
    }

    @Test
    fun frameOutsideTimingWindowStartsNewMessage() {
        val a = assembler()
        val first = a.onFrame(frame(0, "W9XYZ", eom = false))
        // Half a frame late: not a continuation.
        val other = a.onFrame(frame(period + period / 2, "599 001", eom = true))
        assertThat(other.id).isNotEqualTo(first.id)
    }

    @Test
    fun widerFrequencyToleranceForLongerGaps() {
        assertThat(JttyMessageAssembler.continuationFreqToleranceHz(1)).isEqualTo(10f)
        assertThat(JttyMessageAssembler.continuationFreqToleranceHz(3)).isEqualTo(16f)
        val a = assembler()
        a.onFrame(frame(0, "W9XYZ", eom = false, freq = 1500f))
        // 15 Hz away two periods later is inside the 13 Hz... no: 10+3 = 13 -> new message.
        val far = a.onFrame(frame(2 * period, "599 001", eom = true, freq = 1515f))
        assertThat(far.text).isEqualTo("599 001")
    }

    @Test
    fun openMessageIsFlushedAfterThreeSilentPeriods() {
        val a = assembler()
        val first = a.onFrame(frame(0, "W9XYZ", eom = false))
        assertThat(a.tick(3 * period)).isEmpty()
        val flushed = a.tick(5 * period)
        assertThat(flushed).hasSize(1)
        assertThat(flushed[0].id).isEqualTo(first.id)
        assertThat(flushed[0].complete).isTrue()
        assertThat(flushed[0].flushed).isTrue()
        // Once flushed it no longer accepts continuations.
        val later = a.onFrame(frame(5 * period, "599 001", eom = true))
        assertThat(later.id).isNotEqualTo(first.id)
    }

    @Test
    fun clearDropsOpenMessages() {
        val a = assembler()
        val first = a.onFrame(frame(0, "W9XYZ", eom = false))
        a.clear()
        val next = a.onFrame(frame(period, "599 001", eom = true))
        assertThat(next.id).isNotEqualTo(first.id)
    }

    @Test
    fun joinerRules() {
        assertThat(JttyMessageAssembler.joinerFor(previousWasText5 = true, nextIsText5 = true)).isEqualTo("")
        assertThat(JttyMessageAssembler.joinerFor(previousWasText5 = false, nextIsText5 = false)).isEqualTo(" ")
        assertThat(JttyMessageAssembler.joinerFor(previousWasText5 = true, nextIsText5 = false)).isEqualTo(" ")
        assertThat(JttyMessageAssembler.joinerFor(previousWasText5 = false, nextIsText5 = true)).isEqualTo(" ")
    }

    @Test
    fun text5IsReadFromThePayloadTypeBits() {
        assertThat(JttyMessageAssembler.isText5(TEXT5_PAYLOAD)).isTrue()
        assertThat(JttyMessageAssembler.isText5(TEXT5_PAYLOAD or 1L)).isTrue()
        assertThat(JttyMessageAssembler.isText5(CALL_PAYLOAD)).isFalse()
        // EXCH_NUM SERIAL 123 (i2 = 2) and ZONE_LOC3 (i2 = 0): structured.
        assertThat(JttyMessageAssembler.isText5(0x20007B009L)).isFalse()
        assertThat(JttyMessageAssembler.isText5(0x00B790D29L)).isFalse()
    }
}
