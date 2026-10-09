package radio.ks3ckc.jttyaf.jtty

import androidx.annotation.StringRes
import com.k1af.ft8af.R

/**
 * The JTTY text-packing profile (see docs/jtty-protocol.md §3.5). It steers how
 * ambiguous contest text is tokenised into atoms: under RTTY Roundup `599 5` becomes a
 * SERIAL atom spelled `599 005`, under Field Day `1D EMA` becomes a CLASS_SECTION atom.
 * [id] is the wire value shared with the native codec (`jtty_profile_t`).
 */
enum class JttyProfile(val id: Int, @StringRes val labelRes: Int) {
    GENERIC(0, R.string.jtty_profile_generic),
    RTTY_ROUNDUP(1, R.string.jtty_profile_rtty_roundup),
    FIELD_DAY(2, R.string.jtty_profile_field_day),
    ;

    companion object {
        /** Unknown ids (a future build's config) degrade to [GENERIC]. */
        fun fromId(id: Int): JttyProfile = values().firstOrNull { it.id == id } ?: GENERIC
    }
}
