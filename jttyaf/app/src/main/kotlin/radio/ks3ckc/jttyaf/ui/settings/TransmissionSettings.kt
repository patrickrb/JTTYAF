package radio.ks3ckc.jttyaf.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.transmit.MeterProtectionController
import com.k1af.ft8af.transmit.TuneController
import com.k1af.ft8af.transmit.TuneMethod
import radio.ks3ckc.jttyaf.TUNE_LEVEL_INDEPENDENT_KEY
import radio.ks3ckc.jttyaf.TUNE_LEVEL_KEY
import radio.ks3ckc.jttyaf.TUNE_MAX_ON_SECONDS_KEY
import radio.ks3ckc.jttyaf.TUNE_METHOD_KEY
import radio.ks3ckc.jttyaf.jtty.JttyProfile
import radio.ks3ckc.jttyaf.saveTuneLevelForCurrentBand
import radio.ks3ckc.jttyaf.theme.*
import radio.ks3ckc.jttyaf.ui.components.JttyAfIconButton
import radio.ks3ckc.jttyaf.ui.components.JttyAfIcons
import radio.ks3ckc.jttyaf.ui.components.IntSlider
import radio.ks3ckc.jttyaf.ui.components.GlassCard
import radio.ks3ckc.jttyaf.ui.components.SettingsRow

// ALC target window bounds (0-255 normalized ALC scale). The low/high sliders
// keep a [ALC_GAP]-unit gap between the two values; clampAlcLow/clampAlcHigh use
// these both to bound each slider and to keep the coerceIn range non-empty when a
// restored/corrupted config persists an out-of-order pair.
private const val ALC_LOW_MIN = 10
private const val ALC_LOW_MAX = 200
private const val ALC_HIGH_MAX = 250
private const val ALC_GAP = 10

/**
 * Clamp a desired ALC-target *low* value into [ALC_LOW_MIN]..[ALC_LOW_MAX]. The
 * [ALC_GAP]-unit gap below the current [high] is enforced only when [high] leaves
 * room for it (i.e. `high >= ALC_LOW_MIN + ALC_GAP`); otherwise the value is simply
 * clamped to [ALC_LOW_MIN]. This is because the upper bound is floored at
 * [ALC_LOW_MIN] so the range never inverts: a corrupted/restored config with `high`
 * below `ALC_LOW_MIN + ALC_GAP` would otherwise make `high - ALC_GAP` fall under
 * [ALC_LOW_MIN] and crash `coerceIn` with an empty range (in that degenerate case
 * the only non-crashing result is [ALC_LOW_MIN] itself).
 */
internal fun clampAlcLow(desired: Int, high: Int): Int {
    val upper = minOf(ALC_LOW_MAX, high - ALC_GAP).coerceAtLeast(ALC_LOW_MIN)
    return desired.coerceIn(ALC_LOW_MIN, upper)
}

/**
 * Clamp a desired ALC-target *high* value into (`low + ALC_GAP`)..[ALC_HIGH_MAX].
 * The [ALC_GAP]-unit gap above the current [low] is enforced only when [low] leaves
 * room for it (i.e. `low <= ALC_HIGH_MAX - ALC_GAP`); otherwise the lower bound is
 * capped at [ALC_HIGH_MAX] and the value clamps to [ALC_HIGH_MAX].
 */
internal fun clampAlcHigh(desired: Int, low: Int): Int {
    val lower = (low + ALC_GAP).coerceAtMost(ALC_HIGH_MAX)
    return desired.coerceIn(lower, ALC_HIGH_MAX)
}

/**
 * Transmission settings: JTTY contest profile and exchange, TX/RX split, clear-on-band-
 * change, TX protection (auto-volume ALC + SWR halt), and the tune carrier.
 */
@Composable
fun TransmissionSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    var synFrequency by remember { mutableStateOf(GeneralVariables.synFrequency) }
    var clearOnBandModeChange by remember { mutableStateOf(GeneralVariables.clearOnBandModeChange) }

    // JTTY packing profile + contest exchange (see JttyProfile / JttyMacros).
    var jttyProfile by remember { mutableIntStateOf(GeneralVariables.jttyProfile) }
    var echoOwnTx by remember { mutableStateOf(GeneralVariables.jttyEchoOwnTx) }
    var wideSearch by remember { mutableStateOf(GeneralVariables.jttyWideSearch) }

    // TX Protection state
    var autoVolumeEnabled by remember { mutableStateOf(GeneralVariables.autoVolumeEnabled) }
    var swrHaltEnabled by remember { mutableStateOf(GeneralVariables.swrHaltEnabled) }
    var swrHaltThreshold by remember { mutableIntStateOf(GeneralVariables.swrHaltThreshold) }
    var alcTargetLow by remember { mutableIntStateOf(GeneralVariables.alcTargetLow) }
    var alcTargetHigh by remember { mutableIntStateOf(GeneralVariables.alcTargetHigh) }

    // Tune state (issue #408)
    var tuneMaxOnSeconds by remember { mutableIntStateOf(GeneralVariables.tuneMaxOnSeconds) }
    var tuneLevelIndependent by remember { mutableStateOf(GeneralVariables.tuneLevelIndependent) }
    var tuneLevel by remember { mutableIntStateOf(GeneralVariables.tuneLevel) }
    var tuneMethod by remember { mutableIntStateOf(GeneralVariables.tuneMethod) }

    var showTuneMethod by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }

    // Index == TuneMethod.AUTOMATIC/INTERNAL/TONE
    val tuneMethodOptions = listOf(
        stringResource(R.string.tune_method_automatic),
        stringResource(R.string.tune_method_internal),
        stringResource(R.string.tune_method_tone),
    )

    // Index == JttyProfile ordinal (GENERIC / RTTY_ROUNDUP / FIELD_DAY)
    val profileOptions = JttyProfile.values().map { stringResource(it.labelRes) }

    // -- Tune Method Picker (issue #425) --
    if (showTuneMethod) {
        ListPickerDialog(
            title = stringResource(R.string.settings_tune_method),
            items = tuneMethodOptions,
            selectedIndex = TuneMethod.clamp(tuneMethod),
            onDismiss = { showTuneMethod = false },
            onSelect = { index ->
                showTuneMethod = false
                val method = TuneMethod.clamp(index)
                tuneMethod = method
                GeneralVariables.tuneMethod = method
                mainViewModel.databaseOpr.writeConfig(
                    TUNE_METHOD_KEY, method.toString(), null,
                )
            },
        )
    }

    // -- JTTY profile picker --
    if (showProfile) {
        ListPickerDialog(
            title = stringResource(R.string.settings_jtty_profile),
            items = profileOptions,
            selectedIndex = JttyProfile.fromId(jttyProfile).ordinal,
            onDismiss = { showProfile = false },
            onSelect = { index ->
                showProfile = false
                val profile = JttyProfile.values()[index.coerceIn(0, JttyProfile.values().size - 1)]
                jttyProfile = profile.id
                GeneralVariables.jttyProfile = profile.id
                mainViewModel.databaseOpr.writeConfig("jttyProfile", profile.id.toString(), null)
            },
        )
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_transmission),
        onBack = onBack,
    ) {
        // =====================================================================
        // JTTY
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_section_jtty)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_jtty_profile),
                        description = stringResource(R.string.settings_jtty_profile_desc),
                        value = profileOptions[JttyProfile.fromId(jttyProfile).ordinal],
                        showChevron = true,
                        onClick = { showProfile = true },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_jtty_echo_tx),
                        description = stringResource(R.string.settings_jtty_echo_tx_desc),
                        toggle = echoOwnTx,
                        onToggleChange = { checked ->
                            echoOwnTx = checked
                            GeneralVariables.jttyEchoOwnTx = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "jttyEchoOwnTx", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_jtty_wide_search),
                        description = stringResource(R.string.settings_jtty_wide_search_desc),
                        toggle = wideSearch,
                        onToggleChange = { checked ->
                            wideSearch = checked
                            GeneralVariables.jttyWideSearch = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "jttyWideSearch", if (checked) "1" else "0", null,
                            )
                            mainViewModel.jttySignalListener.applySearchSettings()
                        },
                    )
                }
            }
        }

        // =====================================================================
        // TRANSMISSION
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_section_transmission)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_tx_rx_split),
                        description = stringResource(R.string.settings_tx_rx_split_desc),
                        toggle = synFrequency,
                        onToggleChange = { checked ->
                            synFrequency = checked
                            GeneralVariables.synFrequency = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "synFreq", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_clear_on_change),
                        description = stringResource(R.string.settings_clear_on_change_desc),
                        toggle = clearOnBandModeChange,
                        onToggleChange = { checked ->
                            clearOnBandModeChange = checked
                            GeneralVariables.clearOnBandModeChange = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "clearOnBandModeChange", if (checked) "1" else "0", null,
                            )
                        },
                    )
                }
            }
        }

        // =====================================================================
        // TX PROTECTION
        // =====================================================================
        SettingsSection(title = "TX PROTECTION") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = "Auto Volume (ALC)",
                        description = "Automatically adjust TX volume to keep ALC in target range",
                        toggle = autoVolumeEnabled,
                        onToggleChange = { checked ->
                            autoVolumeEnabled = checked
                            GeneralVariables.autoVolumeEnabled = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "autoVolumeEnabled", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    if (autoVolumeEnabled) {
                        SectionDivider()
                        // ALC target range — two values displayed as a label row
                        SettingsRow(
                            label = "ALC Target Range",
                            description = "Low: $alcTargetLow  High: $alcTargetHigh  (0-255 normalized)",
                            value = "$alcTargetLow – $alcTargetHigh",
                        )
                        // Low slider
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Low",
                                style = TextStyle(fontSize = 12.sp, color = TextMuted),
                                modifier = Modifier.width(32.dp),
                            )
                            JttyAfIconButton(
                                onClick = {
                                    val clamped = clampAlcLow(alcTargetLow - 5, alcTargetHigh)
                                    alcTargetLow = clamped
                                    GeneralVariables.alcTargetLow = clamped
                                    mainViewModel.databaseOpr.writeConfig(
                                        "alcTargetLow", clamped.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                JttyAfIcons.Minus(color = Accent, size = 16.dp)
                            }
                            IntSlider(
                                value = alcTargetLow,
                                onValueChange = { v ->
                                    val clamped = clampAlcLow(v, alcTargetHigh)
                                    alcTargetLow = clamped
                                    GeneralVariables.alcTargetLow = clamped
                                },
                                onValueChangeFinished = {
                                    mainViewModel.databaseOpr.writeConfig(
                                        "alcTargetLow", alcTargetLow.toString(), null,
                                    )
                                },
                                valueRange = 10f..200f,
                                modifier = Modifier.weight(1f),
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                            )
                            JttyAfIconButton(
                                onClick = {
                                    val clamped = clampAlcLow(alcTargetLow + 5, alcTargetHigh)
                                    alcTargetLow = clamped
                                    GeneralVariables.alcTargetLow = clamped
                                    mainViewModel.databaseOpr.writeConfig(
                                        "alcTargetLow", clamped.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                JttyAfIcons.Plus(color = Accent, size = 16.dp)
                            }
                        }
                        // High slider
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "High",
                                style = TextStyle(fontSize = 12.sp, color = TextMuted),
                                modifier = Modifier.width(32.dp),
                            )
                            JttyAfIconButton(
                                onClick = {
                                    val clamped = clampAlcHigh(alcTargetHigh - 5, alcTargetLow)
                                    alcTargetHigh = clamped
                                    GeneralVariables.alcTargetHigh = clamped
                                    mainViewModel.databaseOpr.writeConfig(
                                        "alcTargetHigh", clamped.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                JttyAfIcons.Minus(color = Accent, size = 16.dp)
                            }
                            IntSlider(
                                value = alcTargetHigh,
                                onValueChange = { v ->
                                    val clamped = clampAlcHigh(v, alcTargetLow)
                                    alcTargetHigh = clamped
                                    GeneralVariables.alcTargetHigh = clamped
                                },
                                onValueChangeFinished = {
                                    mainViewModel.databaseOpr.writeConfig(
                                        "alcTargetHigh", alcTargetHigh.toString(), null,
                                    )
                                },
                                valueRange = 20f..250f,
                                modifier = Modifier.weight(1f),
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                            )
                            JttyAfIconButton(
                                onClick = {
                                    val clamped = clampAlcHigh(alcTargetHigh + 5, alcTargetLow)
                                    alcTargetHigh = clamped
                                    GeneralVariables.alcTargetHigh = clamped
                                    mainViewModel.databaseOpr.writeConfig(
                                        "alcTargetHigh", clamped.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                JttyAfIcons.Plus(color = Accent, size = 16.dp)
                            }
                        }
                    }
                    SectionDivider()
                    SettingsRow(
                        label = "SWR Protection",
                        description = "Stop transmitting and lock TX if SWR exceeds threshold",
                        toggle = swrHaltEnabled,
                        onToggleChange = { checked ->
                            swrHaltEnabled = checked
                            GeneralVariables.swrHaltEnabled = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "swrHaltEnabled", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    if (swrHaltEnabled) {
                        SectionDivider()
                        val swrRatioStr = MeterProtectionController.normalizedSwrToRatio(swrHaltThreshold)
                        SettingsRow(
                            label = "SWR Threshold",
                            description = "TX halts when SWR exceeds this value",
                            value = swrRatioStr,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "1.5:1",
                                style = TextStyle(fontSize = 12.sp, color = TextMuted),
                            )
                            JttyAfIconButton(
                                onClick = {
                                    val newVal = (swrHaltThreshold - 5).coerceIn(30, 200)
                                    swrHaltThreshold = newVal
                                    GeneralVariables.swrHaltThreshold = newVal
                                    mainViewModel.databaseOpr.writeConfig(
                                        "swrHaltThreshold", newVal.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                JttyAfIcons.Minus(color = Accent, size = 16.dp)
                            }
                            IntSlider(
                                value = swrHaltThreshold,
                                onValueChange = { v ->
                                    swrHaltThreshold = v
                                    GeneralVariables.swrHaltThreshold = v
                                },
                                onValueChangeFinished = {
                                    mainViewModel.databaseOpr.writeConfig(
                                        "swrHaltThreshold", swrHaltThreshold.toString(), null,
                                    )
                                },
                                valueRange = 30f..200f, // ~1.3:1 to ~7.0:1
                                modifier = Modifier.weight(1f),
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                            )
                            JttyAfIconButton(
                                onClick = {
                                    val newVal = (swrHaltThreshold + 5).coerceIn(30, 200)
                                    swrHaltThreshold = newVal
                                    GeneralVariables.swrHaltThreshold = newVal
                                    mainViewModel.databaseOpr.writeConfig(
                                        "swrHaltThreshold", newVal.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                JttyAfIcons.Plus(color = Accent, size = 16.dp)
                            }
                            Text(
                                "7:1",
                                style = TextStyle(fontSize = 12.sp, color = TextMuted),
                            )
                        }
                    }
                }
            }
        }

        // =====================================================================
        // TUNE (issue #408)
        // =====================================================================
        SettingsSection(title = "TUNE") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_tune_method),
                        description = stringResource(R.string.settings_tune_method_desc),
                        value = tuneMethodOptions[TuneMethod.clamp(tuneMethod)],
                        showChevron = true,
                        onClick = { showTuneMethod = true },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = "Tune timeout",
                        description = "Hard cap on the tune carrier — it always stops by itself",
                        value = "${tuneMaxOnSeconds}s",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IntSlider(
                            value = tuneMaxOnSeconds,
                            onValueChange = { v ->
                                val clamped = TuneController.clampMaxOnSeconds(v)
                                tuneMaxOnSeconds = clamped
                                GeneralVariables.tuneMaxOnSeconds = clamped
                            },
                            onValueChangeFinished = {
                                mainViewModel.databaseOpr.writeConfig(
                                    TUNE_MAX_ON_SECONDS_KEY, tuneMaxOnSeconds.toString(), null,
                                )
                            },
                            valueRange = TuneController.MIN_MAX_ON_SECONDS.toFloat()..
                                TuneController.MAX_MAX_ON_SECONDS.toFloat(),
                            modifier = Modifier.weight(1f),
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                        )
                    }
                    SectionDivider()
                    SettingsRow(
                        label = "Independent tune level",
                        description = "Tune at its own drive level (e.g. reduced power) without touching the TX drive",
                        toggle = tuneLevelIndependent,
                        onToggleChange = { checked ->
                            tuneLevelIndependent = checked
                            GeneralVariables.tuneLevelIndependent = checked
                            mainViewModel.databaseOpr.writeConfig(
                                TUNE_LEVEL_INDEPENDENT_KEY, if (checked) "1" else "0", null,
                            )
                        },
                    )
                    if (tuneLevelIndependent) {
                        SectionDivider()
                        SettingsRow(
                            label = "Tune audio level",
                            description = if (GeneralVariables.savePerBandOutputLevel)
                                "Remembered per band (Save output level per band is on)"
                            else "Single level for all bands",
                            value = "$tuneLevel%",
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IntSlider(
                                value = tuneLevel,
                                onValueChange = { v ->
                                    val clamped = v.coerceIn(0, 100)
                                    tuneLevel = clamped
                                    GeneralVariables.tuneLevel = clamped
                                },
                                onValueChangeFinished = {
                                    mainViewModel.databaseOpr.writeConfig(
                                        TUNE_LEVEL_KEY, tuneLevel.toString(), null,
                                    )
                                    // When per-band levels are on, the independent tune
                                    // level is remembered for the current band too — in
                                    // its own map, never the TX one (issue #408).
                                    saveTuneLevelForCurrentBand(mainViewModel.databaseOpr, tuneLevel)
                                },
                                valueRange = 0f..100f,
                                modifier = Modifier.weight(1f),
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                            )
                        }
                    }
                }
            }
        }
    }
}
