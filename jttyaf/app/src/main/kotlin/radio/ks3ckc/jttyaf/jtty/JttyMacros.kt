package radio.ks3ckc.jttyaf.jtty

import java.util.Locale

/**
 * WSJT-X-style JTTY macros. A template may contain:
 *
 *  - `%M` — my callsign
 *  - `%H` — the other station's callsign (the one selected in the operate screen)
 *  - `%E` — the exchange: `599` + the contest serial, at least three digits
 *  - `%Q` — the next queued callsign (not supported yet; expands to the DX call)
 *
 * The defaults are chosen so the common run/S&P overs pack into the native one-frame
 * atoms (`CQ <call> CQ`, `TU <call> CQ`, `<call> TU`, `<call> AGN?`, `TU NOW <call>`,
 * `599 nnn`) rather than slower TEXT5 free text.
 */
object JttyMacros {

    /** F1..F8 defaults, in order. */
    val DEFAULTS: List<String> = listOf(
        "CQ %M CQ",        // F1  run: call CQ
        "%H %E",           // F2  exchange
        "TU %M CQ",        // F3  run: thank + next
        "%M",              // F4  S&P: my call
        "%H TU",           // F5  confirm the other station
        "%H AGN?",         // F6  ask for a repeat
        "NR?",             // F7  ask for the number
        "TU NOW %M",       // F8  moving on
    )

    /** Short chip labels for the macro row. */
    val LABELS: List<String> = listOf("CQ", "EXCH", "TU CQ", "MY", "TU", "AGN?", "NR?", "NOW")

    /** The template for F[index+1]: the operator's override when set, else the default. */
    fun templateAt(index: Int, overrides: Array<String?>): String {
        val custom = overrides.getOrNull(index)?.trim()
        return if (!custom.isNullOrEmpty()) custom else DEFAULTS[index.coerceIn(0, DEFAULTS.size - 1)]
    }

    /**
     * Expand [template]. Empty substitutions collapse the surrounding doubled spaces so
     * a missing DX call leaves `599 001` rather than ` 599 001`.
     */
    fun expand(template: String, myCall: String?, dxCall: String?, serial: Int): String {
        val me = myCall?.trim()?.uppercase(Locale.US).orEmpty()
        val dx = dxCall?.trim()?.uppercase(Locale.US).orEmpty()
        return template
            .replace("%M", me)
            .replace("%H", dx)
            .replace("%Q", dx)
            .replace("%E", exchange(serial))
            .replace(Regex(" {2,}"), " ")
            .trim()
            .uppercase(Locale.US)
    }

    /** `599 001` — the RTTY-style exchange with a serial of at least three digits. */
    fun exchange(serial: Int): String =
        String.format(Locale.US, "599 %03d", serial.coerceIn(0, 16383))

    /** Whether [template] needs a DX callsign to make sense (so the UI can grey it out). */
    fun needsDxCall(template: String): Boolean = template.contains("%H") || template.contains("%Q")
}
