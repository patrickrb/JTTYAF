package radio.ks3ckc.jttyaf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.database.OperationBand
import com.k1af.ft8af.rigs.BaseRigOperation
import com.k1af.ft8af.rigs.CatConnectionState
import radio.ks3ckc.jttyaf.theme.BgApp
import radio.ks3ckc.jttyaf.ui.components.FrequencyPickerSheet
import radio.ks3ckc.jttyaf.ui.components.JttyAfTab
import radio.ks3ckc.jttyaf.ui.components.TabBar
import radio.ks3ckc.jttyaf.ui.components.TransmitGlow
import radio.ks3ckc.jttyaf.ui.components.TxStrip
import radio.ks3ckc.jttyaf.ui.components.formatMhz
import radio.ks3ckc.jttyaf.ui.components.selectBandIndex
import radio.ks3ckc.jttyaf.ui.components.shouldShowCatChip
import radio.ks3ckc.jttyaf.ui.logbook.LogbookScreen
import radio.ks3ckc.jttyaf.ui.operate.OperateScreen
import radio.ks3ckc.jttyaf.ui.rateprompt.RatePromptHost
import radio.ks3ckc.jttyaf.ui.settings.SettingsScreen
import radio.ks3ckc.jttyaf.ui.waterfall.WaterfallScreen

/**
 * The app shell: OPERATE / WATERFALL / LOG / SETTINGS tabs above the TX strip and tab
 * bar. OPERATE (the live JTTY receive text plus the keyboard, macros and Send) is the
 * landing tab; the strip shows what the radio is doing and carries the band picker,
 * Tune and TX volume so they are reachable from every tab.
 */
@Composable
fun JttyAfApp(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    var activeTab by rememberSaveable { mutableStateOf(JttyAfTab.OPERATE) }

    // Transmit state: a JTTY over or the tune carrier.
    val isTransmitting by mainViewModel.jttyTransmitter.isTransmitting.observeAsState(false)
    val txProgress by mainViewModel.jttyTransmitter.txProgress.observeAsState(0f)
    val txText by mainViewModel.jttyTransmitter.currentText.observeAsState(null)
    val isTuning by mainViewModel.tuneOperator.mutableIsTuning.observeAsState(false)
    val tuneRemainingSec by mainViewModel.tuneOperator.mutableTuneRemainingSec.observeAsState(0)
    val qsoCompletedAt by mainViewModel.mutableQsoCompletedAt.observeAsState()

    // CAT connection status for the TX-strip chip. Hidden for VOX / audio-only
    // setups (see shouldShowCatChip); tap reconnects (handy for Bluetooth, which
    // often only connects on the second attempt).
    val catState by mainViewModel.mutableCatConnectionState.observeAsState(CatConnectionState.DISCONNECTED)
    // Observe control mode so the chip shows/hides immediately when the user
    // switches VOX <-> CAT/RTS/DTR in Settings (seeds from the current value).
    val controlMode by GeneralVariables.mutableControlMode.observeAsState(GeneralVariables.controlMode)
    val showCatChip = shouldShowCatChip(controlMode, catState)

    // TX Volume state — observe LiveData so hardware buttons, ALC auto-volume,
    // and the settings slider all update the inline slider bidirectionally.
    val volumeLive by GeneralVariables.mutableVolumePercent.observeAsState(
        GeneralVariables.volumePercent,
    )
    var txVolume by remember { mutableIntStateOf((GeneralVariables.volumePercent * 100).toInt()) }
    LaunchedEffect(volumeLive) {
        txVolume = ((volumeLive ?: GeneralVariables.volumePercent) * 100).toInt()
    }

    // Inline volume slider visibility — observed so toggling in Settings
    // immediately shows/hides the slider on the main screen.
    val showVolumeSliderLive by GeneralVariables.mutableShowTxVolumeSlider.observeAsState(
        GeneralVariables.showTxVolumeSlider,
    )
    var showVolumeSlider by remember { mutableStateOf(GeneralVariables.showTxVolumeSlider) }
    LaunchedEffect(showVolumeSliderLive) {
        showVolumeSlider = showVolumeSliderLive ?: GeneralVariables.showTxVolumeSlider
    }

    // Consume the one-shot QSO-logged signal so LiveData doesn't replay it on
    // recomposition / resubscription (RatePromptHost turns it into a token first).
    LaunchedEffect(qsoCompletedAt) {
        if (qsoCompletedAt != null) {
            mainViewModel.mutableQsoCompletedAt.postValue(null)
        }
    }

    // Frequency picker sheet state
    var showFrequencyPicker by rememberSaveable { mutableStateOf(false) }

    // Pill label combines MHz frequency and band name, e.g. "14.090 MHz · 20m".
    // bandIndex is observed so the pill recomposes when the user retunes.
    val bandIndex by GeneralVariables.mutableBandChange.observeAsState(GeneralVariables.bandListIndex)
    val rxOffset by GeneralVariables.mutableBaseFrequency.observeAsState(GeneralVariables.getBaseFrequency())
    val freq = GeneralVariables.band
    val bandName = OperationBand.bandList.getOrNull(bandIndex)?.waveLength
        ?: OperationBand.bandList.firstOrNull { it.band == freq }?.waveLength
        ?: BaseRigOperation.getMeterFromFreq(freq)
        ?: ""
    val frequencyLabel = buildString {
        append(formatMhz(freq))
        append(" MHz")
        if (bandName.isNotBlank()) {
            append(" · ")
            append(bandName)
        }
    }

    // Observe SWR lockout state
    val swrLocked by mainViewModel.meterProtectionController.swrLockout.observeAsState(false)
    val lockoutSwrRatio by mainViewModel.meterProtectionController.lockoutSwrRatio.observeAsState("")

    Box(modifier = Modifier.fillMaxSize().background(BgApp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // SWR lockout banner — red warning spanning the top.
            if (swrLocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFCC2222))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.swr_lockout_title, lockoutSwrRatio),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = stringResource(R.string.swr_lockout_body),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { mainViewModel.meterProtectionController.clearSwrLockout() },
                    ) {
                        Text(
                            stringResource(R.string.swr_lockout_dismiss),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // Note: AndroidView-wrapped legacy views (waterfall/columnar) interact badly with
            // AnimatedContent's graphicsLayer translations during enter/exit, so tab switching
            // here is a plain swap. The TabBar selection itself still animates.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activeTab) {
                    JttyAfTab.OPERATE -> OperateScreen(mainViewModel)
                    JttyAfTab.WATERFALL -> WaterfallScreen(mainViewModel)
                    JttyAfTab.LOG -> LogbookScreen(mainViewModel)
                    JttyAfTab.SETTINGS -> SettingsScreen(mainViewModel)
                }
            }

            // TX status strip — always visible above the bottom bar.
            TxStrip(
                isTransmitting = isTransmitting,
                isTuning = isTuning,
                tuneRemainingSec = tuneRemainingSec,
                frequencyLabel = frequencyLabel,
                rxOffsetHz = rxOffset.toInt(),
                txText = txText,
                txProgress = txProgress,
                catState = catState,
                showCatChip = showCatChip,
                txVolume = txVolume,
                showVolumeSlider = showVolumeSlider,
                onToggleTune = {
                    // Toggle (WSJT-X style latching Tune): tap to key the carrier, tap again
                    // to stop. The rig's ATU takes precedence when the operator chose it;
                    // startTune() toasts the reason when blocked.
                    if (isTuning) {
                        mainViewModel.tuneOperator.stopTune()
                    } else if (!mainViewModel.tryStartTuneViaAtu()) {
                        mainViewModel.tuneOperator.startTune()
                    }
                },
                onStopTx = { mainViewModel.jttyTransmitter.cancel() },
                onVolumeChange = { newVolume ->
                    txVolume = newVolume
                    GeneralVariables.volumePercent = newVolume / 100f
                    GeneralVariables.mutableVolumePercent.postValue(newVolume / 100f)
                },
                onVolumeChangeFinished = {
                    mainViewModel.databaseOpr.writeConfig("volumeValue", txVolume.toString(), null)
                    mainViewModel.baseRig?.connector?.setRFVolume(txVolume)
                    saveOutputLevelForCurrentBand(mainViewModel.databaseOpr, txVolume)
                },
                onReconnectCat = { mainViewModel.reconnectRig() },
                onOpenFrequencyPicker = { showFrequencyPicker = true },
            )

            TabBar(
                activeTab = activeTab,
                onTabSelected = { activeTab = it },
            )
        }

        // Transmit breathing border — sibling overlay so its per-frame invalidations
        // don't bubble into the waterfall composable. Pointer events pass through.
        // The tune carrier is a real transmission (issue #408): the operator must
        // never be unsure whether the rig is keyed.
        TransmitGlow(isTransmitting = isTransmitting || isTuning)

        // Frequency picker — sibling overlay so the scrim and sheet sit above the
        // tab bar and TxStrip.
        FrequencyPickerSheet(
            visible = showFrequencyPicker,
            currentBandIndex = bandIndex,
            onDismiss = { showFrequencyPicker = false },
            onSelect = { idx ->
                selectBandIndex(mainViewModel, context, idx)
                showFrequencyPicker = false
            },
        )

        // "Enjoying JTTYAF?" prompt after a logged QSO, when the log says it has earned it.
        RatePromptHost(
            qsoCompletedAt = qsoCompletedAt,
            isTransmitting = isTransmitting || isTuning,
            database = { mainViewModel.databaseOpr?.db },
        )
    }
}
