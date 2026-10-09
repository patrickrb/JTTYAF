package radio.ks3ckc.jttyaf.ui.operate

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.jttyaf.jtty.JttyMessage
import radio.ks3ckc.jttyaf.jtty.JttyTransmitter

class OperateScreenLogicTest {

    private val resolve: (Int) -> String = { "res:$it" }
    private val framesFormat: (Int, Float) -> String = { n, s -> "$n frames ${"%.1f".format(s)} s" }

    @Test
    fun txHintShowsFrameCountWhenSendable() {
        assertThat(txHint(JttyTransmitter.Reject.NONE, 2, resolve, framesFormat))
            .isEqualTo("2 frames 3.8 s")
    }

    @Test
    fun txHintIsHiddenForEmptyAndBusy() {
        assertThat(txHint(JttyTransmitter.Reject.EMPTY, 0, resolve, framesFormat)).isNull()
        assertThat(txHint(JttyTransmitter.Reject.BUSY, 0, resolve, framesFormat)).isNull()
        assertThat(txHint(JttyTransmitter.Reject.TUNE_ACTIVE, 0, resolve, framesFormat)).isNull()
    }

    @Test
    fun txHintExplainsRejections() {
        assertThat(txHint(JttyTransmitter.Reject.TOO_LONG, 0, resolve, framesFormat)).startsWith("res:")
        assertThat(txHint(JttyTransmitter.Reject.UNPACKABLE, 0, resolve, framesFormat)).startsWith("res:")
    }

    @Test
    fun serialWrapsInsideTheFourteenBitField() {
        assertThat(nextSerial(1)).isEqualTo(2)
        assertThat(nextSerial(16382)).isEqualTo(16383)
        assertThat(nextSerial(16383)).isEqualTo(1)
    }

    @Test
    fun otherCallsignInLinePrefersTheStationThatIsNotMe() {
        assertThat(JttyMessage.otherCallsignIn("CQ K1ABC CQ", "W9XYZ")).isEqualTo("K1ABC")
        assertThat(JttyMessage.otherCallsignIn("K1ABC 599 001 W9XYZ", "K1ABC")).isEqualTo("W9XYZ")
        assertThat(JttyMessage.otherCallsignIn("CQ K1ABC CQ", "k1abc")).isNull()
        assertThat(JttyMessage.otherCallsignIn("599 001", null)).isNull()
    }
}
