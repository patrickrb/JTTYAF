package radio.ks3ckc.jttyaf.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import radio.ks3ckc.jttyaf.theme.Accent
import radio.ks3ckc.jttyaf.theme.GeistMonoFamily
import radio.ks3ckc.jttyaf.theme.TextPrimary

/**
 * JTTYAF wordmark — Geist Mono 600, "JTTY" in mono white with the "AF" family
 * suffix in the emerald accent.
 *
 * Use sparingly: splash screen, About screen, share-card surfaces.
 */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 36.sp,
    baseColor: Color = TextPrimary,
    accentColor: Color = Accent,
) {
    Row(modifier = modifier) {
        Text(
            text = "JTTY",
            color = baseColor,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.04.em,
        )
        Text(
            text = "AF",
            color = accentColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.04.em,
        )
    }
}
