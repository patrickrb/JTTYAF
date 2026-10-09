package radio.ks3ckc.jttyaf.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import com.k1af.ft8af.rigs.CatConnectionState
import radio.ks3ckc.jttyaf.theme.*

/**
 * Clamp a volume value after a +/- step to the 0–100 range.
 * Extracted so it can be unit-tested without Compose.
 */
internal fun clampVolume(current: Int, delta: Int): Int =
    (current + delta).coerceIn(0, 100)

/**
 * Label for the TUNE chip: the plain label when idle, "label countdown" while
 * the carrier is up (e.g. "Tune 7s") so the operator sees the safety timeout
 * running. Extracted so it can be unit-tested without Compose.
 */
internal fun tuneChipLabel(label: String, isTuning: Boolean, remainingSec: Int): String =
    if (isTuning) "$label ${remainingSec.coerceAtLeast(0)}s" else label

/**
 * Which of the three status states the strip's status row shows. Extracted so the
 * mapping is unit-testable without Compose. The pulse-dot color is the accent for
 * Tuning/Transmitting and the signal tint for Receiving.
 */
internal data class TxStatusVisuals(
    val labelRes: Int,
    val listening: Boolean,
)

internal fun txStatusVisuals(isTransmitting: Boolean, isTuning: Boolean): TxStatusVisuals = when {
    isTuning -> TxStatusVisuals(R.string.tx_status_tuning, listening = false)
    isTransmitting -> TxStatusVisuals(R.string.tx_status_transmitting, listening = false)
    else -> TxStatusVisuals(R.string.tx_status_listening, listening = true)
}

/**
 * The bottom-anchored TX strip for a keyboard mode. Rows: status + CAT chip (with the
 * text on the air and a progress bar while transmitting), then the band/offset card beside
 * the Tune button, then the optional inline TX-volume slider. The keyboard, macros and
 * Send button live in the Operate screen above — this strip only shows what the radio is
 * doing and the controls that are needed on every tab.
 */
@Composable
fun TxStrip(
    isTransmitting: Boolean,
    isTuning: Boolean,
    tuneRemainingSec: Int,
    frequencyLabel: String,
    rxOffsetHz: Int,
    txText: String?,
    txProgress: Float,
    catState: CatConnectionState,
    showCatChip: Boolean,
    txVolume: Int,
    showVolumeSlider: Boolean,
    onToggleTune: () -> Unit,
    onStopTx: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    onReconnectCat: () -> Unit,
    onOpenFrequencyPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isTransmitting || isTuning) {
        Brush.horizontalGradient(listOf(Accent.copy(alpha = 0.12f), Accent.copy(alpha = 0.04f)))
    } else {
        Brush.horizontalGradient(listOf(BgSurface, BgSurface))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .drawBehind {
                drawLine(
                    color = Border,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1f,
                )
            }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- Row 1: status + CAT chip ----
        OperateStatusRow(
            isTransmitting = isTransmitting,
            isTuning = isTuning,
            txText = txText,
            showCatChip = showCatChip,
            catState = catState,
            onReconnectCat = onReconnectCat,
            onStopTx = onStopTx,
        )
        if (isTransmitting) {
            LinearProgressIndicator(
                progress = { txProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Accent,
                trackColor = BgSurface3,
            )
        }

        // ---- Row 2: band / RX-TX offset card · Tune ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BandOffsetRow(
                frequencyLabel = frequencyLabel,
                rxOffsetHz = rxOffsetHz,
                onOpenFrequencyPicker = onOpenFrequencyPicker,
                modifier = Modifier.weight(1f),
            )
            val tuneEnabled = isTuning || !isTransmitting
            SecondaryButton(
                modifier = Modifier.weight(0.4f),
                label = tuneChipLabel(stringResource(R.string.tune_button), isTuning, tuneRemainingSec),
                active = isTuning,
                enabled = tuneEnabled,
                onClick = onToggleTune,
            )
        }

        // ---- Inline TX volume slider (togglable from Settings) ----
        if (showVolumeSlider) {
            TxVolumeRow(
                txVolume = txVolume,
                onVolumeChange = onVolumeChange,
                onVolumeChangeFinished = onVolumeChangeFinished,
            )
        }
    }
}

/**
 * Row 1 — the status line (pulse dot + plain-language state, plus the text on the air
 * while transmitting) with the optional CAT chip on the right and a STOP button while a
 * JTTY over is playing.
 */
@Composable
internal fun OperateStatusRow(
    isTransmitting: Boolean,
    isTuning: Boolean,
    txText: String?,
    showCatChip: Boolean,
    catState: CatConnectionState,
    onReconnectCat: () -> Unit,
    onStopTx: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = txStatusVisuals(isTransmitting, isTuning)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseDot(color = if (status.listening) Signal else Accent)
            Column {
                Text(
                    text = stringResource(status.labelRes),
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isTransmitting && !txText.isNullOrBlank()) {
                    Text(
                        text = txText,
                        color = Accent,
                        fontSize = 12.sp,
                        fontFamily = GeistMonoFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isTransmitting) {
                val stopLabel = stringResource(R.string.operate_stop)
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(StatusBad)
                        .clickable(onClick = onStopTx)
                        .semantics { role = Role.Button; contentDescription = stopLabel }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stopLabel,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = InterFamily,
                    )
                }
            }
            if (showCatChip) {
                CatStatusChip(state = catState, onReconnect = onReconnectCat)
            }
        }
    }
}

/** Row 2 — the tappable band card (opens the frequency picker) showing the dial and RX/TX offset. */
@Composable
internal fun BandOffsetRow(
    frequencyLabel: String,
    rxOffsetHz: Int,
    onOpenFrequencyPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface3)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .clickable { onOpenFrequencyPicker() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = frequencyLabel,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = "${stringResource(R.string.tx_rx_offset)} $rxOffsetHz Hz",
                color = TextFaint,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterFamily,
                letterSpacing = 0.4.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
        JttyAfIcons.ChevronDown(size = 14.dp, color = TextMuted, strokeWidth = 2f)
    }
}

/** The inline TX-volume slider (− / slider / +): togglable from Settings. */
@Composable
internal fun TxVolumeRow(
    txVolume: Int,
    onVolumeChange: (Int) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val volumeDecrease = stringResource(R.string.tx_volume_decrease)
    val volumeIncrease = stringResource(R.string.tx_volume_increase)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BgSurface3)
                .semantics { role = Role.Button; contentDescription = volumeDecrease }
                .clickable {
                    onVolumeChange(clampVolume(txVolume, -5))
                    onVolumeChangeFinished()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "−",
                color = TextMuted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistMonoFamily,
            )
        }

        IntSlider(
            value = txVolume,
            onValueChange = { v -> onVolumeChange(v.coerceIn(0, 100)) },
            onValueChangeFinished = onVolumeChangeFinished,
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
            thumbColor = Accent,
            activeTrackColor = Accent,
        )

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BgSurface3)
                .semantics { role = Role.Button; contentDescription = volumeIncrease }
                .clickable {
                    onVolumeChange(clampVolume(txVolume, 5))
                    onVolumeChangeFinished()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                color = TextMuted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistMonoFamily,
            )
        }

        Text(
            text = "${txVolume}%",
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.02.sp,
        )
    }
}

/** A small 52dp secondary button (Tune): accent-tinted when active, muted otherwise. */
@Composable
internal fun SecondaryButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Accent.copy(alpha = 0.18f) else BgSurface3)
            .border(
                1.dp,
                if (active) Accent.copy(alpha = 0.28f) else Border,
                RoundedCornerShape(12.dp),
            )
            // toggleable (not clickable) so TalkBack announces the on/off state — the
            // button's label alone can't convey whether the carrier is up.
            .toggleable(value = active, enabled = enabled, onValueChange = { onClick() })
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                active -> Accent
                enabled -> TextMuted
                else -> TextMuted.copy(alpha = 0.4f)
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFamily,
            // The active "Tune 60s" countdown can be wider than the space available on a
            // narrow phone; allow it to wrap to a second line instead of clipping the seconds.
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun PulseDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )
    val pulseSize by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseSize",
    )

    Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((7 + pulseSize * 2).dp)
                .clip(CircleShape)
                .background(color.copy(alpha = pulseAlpha * 0.18f))
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}
