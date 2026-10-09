package radio.ks3ckc.jttyaf.jtty

import java.util.Locale

/**
 * One line of the receive text: a JTTY message assembled from 1..16 frames (or our own
 * transmission echoed into the pane when [isOwnTx]).
 *
 * @property id          monotonic per listener session; stable across updates so the UI
 *                       can key rows and animate in-place growth of a multi-frame message
 * @property utcMillis   wall-clock time of the first frame
 * @property freqHz      lowest-tone audio frequency of the signal
 * @property snrDb       S/N (2500 Hz reference) of the strongest frame so far
 * @property text        the rendered message so far (`...` marks a skipped frame period)
 * @property complete    true once the EOM frame arrived (or the message was flushed as
 *                       incomplete after 3 silent frame periods — see [flushed])
 * @property flushed     true when the message ended without an EOM frame
 * @property frames      how many frames contributed
 * @property isOwnTx     the line is our own transmitted text, not a decode
 */
data class JttyMessage(
    val id: Long,
    val utcMillis: Long,
    val freqHz: Float,
    val snrDb: Float,
    val text: String,
    val complete: Boolean,
    val flushed: Boolean = false,
    val frames: Int = 1,
    val isOwnTx: Boolean = false,
) {
    /** `HHMMSS  1500  -12  CQ K1ABC CQ` — the ALL.TXT-style one-liner. */
    fun displayLine(): String {
        val t = utcMillis / 1000
        val hh = (t / 3600) % 24
        val mm = (t % 3600) / 60
        val ss = t % 60
        val who = if (isOwnTx) "TX" else String.format(Locale.US, "%+3.0f", snrDb)
        return String.format(Locale.US, "%02d%02d%02d %5.0f %4s %s", hh, mm, ss, freqHz, who, text)
    }

    companion object {
        /** Characters a JTTY callsign atom can contain (standard calls only, no `/`). */
        private val CALL_REGEX = Regex("^[A-Z0-9]{1,2}[0-9][A-Z]{1,3}$")

        /**
         * The first standard callsign in [text] that is not [myCall] — what a tap on the
         * line means by "work this station". Null when the line carries none.
         */
        fun otherCallsignIn(text: String, myCall: String?): String? {
            val mine = myCall?.trim()?.uppercase().orEmpty()
            return text.uppercase().split(' ')
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && it != mine && CALL_REGEX.matches(it) }
        }
    }
}
