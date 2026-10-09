package radio.ks3ckc.jttyaf.jtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JttyMacrosTest {

    @Test
    fun expandsAllPlaceholders() {
        assertThat(JttyMacros.expand("%H %E %M", "k1abc", "w9xyz", 7))
            .isEqualTo("W9XYZ 599 007 K1ABC")
    }

    @Test
    fun missingDxCallCollapsesSpaces() {
        assertThat(JttyMacros.expand("%H %E", "K1ABC", null, 123)).isEqualTo("599 123")
        assertThat(JttyMacros.expand("%H TU", "K1ABC", "", 1)).isEqualTo("TU")
    }

    @Test
    fun exchangeHasAtLeastThreeDigitsAndIsBounded() {
        assertThat(JttyMacros.exchange(1)).isEqualTo("599 001")
        assertThat(JttyMacros.exchange(1234)).isEqualTo("599 1234")
        assertThat(JttyMacros.exchange(99999)).isEqualTo("599 16383")
        assertThat(JttyMacros.exchange(-4)).isEqualTo("599 000")
    }

    @Test
    fun defaultsPackIntoOneFrameAtoms() {
        // The defaults are chosen to render as the native call-action atoms.
        assertThat(JttyMacros.expand(JttyMacros.DEFAULTS[0], "K1ABC", null, 1)).isEqualTo("CQ K1ABC CQ")
        assertThat(JttyMacros.expand(JttyMacros.DEFAULTS[2], "K1ABC", null, 1)).isEqualTo("TU K1ABC CQ")
        assertThat(JttyMacros.expand(JttyMacros.DEFAULTS[4], "K1ABC", "W9XYZ", 1)).isEqualTo("W9XYZ TU")
        assertThat(JttyMacros.expand(JttyMacros.DEFAULTS[5], "K1ABC", "W9XYZ", 1)).isEqualTo("W9XYZ AGN?")
        assertThat(JttyMacros.expand(JttyMacros.DEFAULTS[7], "K1ABC", "W9XYZ", 1)).isEqualTo("TU NOW K1ABC")
    }

    @Test
    fun overridesWinOnlyWhenNonBlank() {
        val overrides = arrayOfNulls<String>(8)
        assertThat(JttyMacros.templateAt(1, overrides)).isEqualTo(JttyMacros.DEFAULTS[1])
        overrides[1] = "  "
        assertThat(JttyMacros.templateAt(1, overrides)).isEqualTo(JttyMacros.DEFAULTS[1])
        overrides[1] = "%H 5NN %E"
        assertThat(JttyMacros.templateAt(1, overrides)).isEqualTo("%H 5NN %E")
        // Out of range indices clamp instead of throwing.
        assertThat(JttyMacros.templateAt(99, overrides)).isEqualTo(JttyMacros.DEFAULTS.last())
    }

    @Test
    fun needsDxCallDetectsPlaceholders() {
        assertThat(JttyMacros.needsDxCall("CQ %M CQ")).isFalse()
        assertThat(JttyMacros.needsDxCall("%H %E")).isTrue()
        assertThat(JttyMacros.needsDxCall("%Q TU")).isTrue()
    }
}
