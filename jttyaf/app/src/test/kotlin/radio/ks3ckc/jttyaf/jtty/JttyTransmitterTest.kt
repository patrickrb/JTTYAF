package radio.ks3ckc.jttyaf.jtty

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The keyboard transmitter against a fake codec and synchronous worker: validation,
 * the key-down / play / key-up order with the un-key guaranteed, cancellation and the
 * completion callbacks. Robolectric only so the LiveData posts land on a main looper
 * the test can drain.
 */
@RunWith(RobolectricTestRunner::class)
class JttyTransmitterTest {

    /** Deliver the LiveData values the transmitter posted from the (synchronous) worker. */
    private fun drainMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    /** Packs 5 characters per frame; refuses "BAD". */
    private class FakeCodec : JttyCodec {
        var encoded: String? = null
        override fun packFrames(text: String, profile: Int): Int =
            if (text.contains("BAD")) -3 else (text.length + 4) / 5
        override fun encode(text: String, profile: Int, f0Hz: Float, sampleRate: Int, amplitude: Float): FloatArray {
            encoded = text
            return FloatArray(packFrames(text, profile) * 59 * 384)
        }
        override fun newReceiver(sampleRate: Int): JttyCodec.Receiver = throw UnsupportedOperationException()
    }

    private class Recorder : JttyTransmitter.Keyer, JttyTransmitter.Player {
        val events = ArrayList<String>()
        var playResult = true
        var throwOnPlay = false
        override fun keyDown() { events += "keyDown" }
        override fun keyUp() { events += "keyUp" }
        override fun play(buffer: FloatArray, sampleRate: Int): Boolean {
            events += "play(${buffer.size}@$sampleRate)"
            if (throwOnPlay) throw IllegalStateException("boom")
            return playResult
        }
        override fun cancel() { events += "cancel" }
    }

    private fun transmitter(codec: FakeCodec, rec: Recorder, tuneActive: Boolean = false) =
        JttyTransmitter(
            codec = codec,
            keyer = rec,
            player = rec,
            tuneActive = { tuneActive },
            sampleRateSource = { 12_000 },
            f0Source = { 1500f },
            profileSource = { 0 },
            settleDelayMsSource = { 0L },
            log = {},
            clock = { 0L },
            workerRunner = { it.run() },
        )

    @Test
    fun checkReportsFramesAndRejections() {
        val tx = transmitter(FakeCodec(), Recorder())
        assertThat(tx.check("")).isEqualTo(JttyTransmitter.Reject.EMPTY to 0)
        assertThat(tx.check("hello world")).isEqualTo(JttyTransmitter.Reject.NONE to 3)
        assertThat(tx.check("x".repeat(81))).isEqualTo(JttyTransmitter.Reject.TOO_LONG to 0)
        assertThat(tx.check("BAD")).isEqualTo(JttyTransmitter.Reject.UNPACKABLE to 0)
    }

    @Test
    fun sendKeysPlaysAndUnkeysInOrderAndReportsText() {
        val codec = FakeCodec()
        val rec = Recorder()
        val tx = transmitter(codec, rec)
        var transmitted: String? = null
        var ended = 0
        tx.setOnTransmitted { transmitted = it }
        tx.setOnTxEnd { ended++ }

        assertThat(tx.send("  cq   k1abc cq ")).isEqualTo(JttyTransmitter.Reject.NONE)
        drainMainLooper()

        assertThat(codec.encoded).isEqualTo("CQ K1ABC CQ")
        assertThat(rec.events).containsExactly("keyDown", "play(${3 * 59 * 384}@12000)", "keyUp").inOrder()
        assertThat(transmitted).isEqualTo("CQ K1ABC CQ")
        assertThat(ended).isEqualTo(1)
        assertThat(tx.isTransmittingNow()).isFalse()
        assertThat(tx.txProgress.value).isEqualTo(1f)
    }

    @Test
    fun playFailureStillUnkeysAndDoesNotReportTransmitted() {
        val rec = Recorder().apply { throwOnPlay = true }
        val tx = transmitter(FakeCodec(), rec)
        var transmitted: String? = null
        var ended = 0
        tx.setOnTransmitted { transmitted = it }
        tx.setOnTxEnd { ended++ }
        tx.send("HELLO")
        drainMainLooper()
        assertThat(rec.events.last()).isEqualTo("keyUp")
        assertThat(transmitted).isNull()
        assertThat(ended).isEqualTo(1)
        assertThat(tx.txProgress.value).isEqualTo(0f)
    }

    @Test
    fun rejectedWhileTuneCarrierIsUp() {
        val rec = Recorder()
        val tx = transmitter(FakeCodec(), rec, tuneActive = true)
        assertThat(tx.send("HELLO")).isEqualTo(JttyTransmitter.Reject.TUNE_ACTIVE)
        assertThat(rec.events).isEmpty()
    }

    @Test
    fun cancelWhenIdleIsNoOp() {
        val rec = Recorder()
        val tx = transmitter(FakeCodec(), rec)
        tx.cancel()
        assertThat(rec.events).isEmpty()
    }

    @Test
    fun progressFractionIsCappedBelowOne() {
        assertThat(JttyTransmitter.txProgressFraction(0, 1000)).isEqualTo(0f)
        assertThat(JttyTransmitter.txProgressFraction(500, 1000)).isEqualTo(0.5f)
        assertThat(JttyTransmitter.txProgressFraction(5000, 1000)).isEqualTo(JttyTransmitter.MAX_TICKER_PROGRESS)
        assertThat(JttyTransmitter.txProgressFraction(10, 0)).isEqualTo(0f)
    }

    @Test
    fun normalizeUppercasesAndCollapsesWhitespace() {
        assertThat(JttyTransmitter.normalize("  cq\t k1abc   cq ")).isEqualTo("CQ K1ABC CQ")
    }
}
