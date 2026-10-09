package radio.ks3ckc.jttyaf.ui.operate

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.log.QSLRecord
import com.k1af.ft8af.timer.UtcTimer
import radio.ks3ckc.jttyaf.jtty.JttyMacros
import radio.ks3ckc.jttyaf.jtty.JttyMessage
import radio.ks3ckc.jttyaf.jtty.JttyTransmitter
import radio.ks3ckc.jttyaf.theme.*
import radio.ks3ckc.jttyaf.ui.components.GlassCard
import radio.ks3ckc.jttyaf.ui.components.TopBar
import java.util.Locale

/** Frame duration in seconds, for the "n frames · x s" hint under the TX field. */
private const val FRAME_SECONDS = 1.888f

/**
 * The hint under the TX field: how the typed text will go out, or why it cannot.
 * Null hides the hint. Extracted so the mapping is unit-testable without Compose.
 */
internal fun txHint(reject: JttyTransmitter.Reject, frames: Int, resolve: (Int) -> String,
                    framesFormat: (Int, Float) -> String): String? = when (reject) {
    JttyTransmitter.Reject.NONE -> framesFormat(frames, frames * FRAME_SECONDS)
    JttyTransmitter.Reject.EMPTY -> null
    JttyTransmitter.Reject.TOO_LONG -> resolve(R.string.operate_too_long)
    JttyTransmitter.Reject.UNPACKABLE -> resolve(R.string.operate_unpackable)
    JttyTransmitter.Reject.BUSY, JttyTransmitter.Reject.TUNE_ACTIVE -> null
}

/**
 * The next serial after logging a QSO: increments and stays within the 14-bit SERIAL
 * field the EXCH_NUM atom can carry. Pure; unit-tested.
 */
internal fun nextSerial(current: Int): Int = if (current >= 16383) 1 else current + 1

/**
 * Build the logbook record for a JTTY QSO logged from the operate screen. RST is
 * stored in the report columns (599 both ways by default — the exchange JTTY sends);
 * the TX audio offset is the lowest tone. Pure so it is unit-testable.
 */
internal fun buildJttyQsoRecord(
    nowMillis: Long,
    myCall: String,
    myGrid: String,
    dxCall: String,
    sentRst: Int,
    receivedRst: Int,
    bandHz: Long,
    offsetHz: Int,
): QSLRecord = QSLRecord(
    nowMillis, nowMillis, myCall, myGrid, dxCall, "", sentRst, receivedRst,
    GeneralVariables.OPERATING_MODE_NAME, bandHz, offsetHz,
)

/**
 * The Operate tab: the live receive text on top, the keyboard below — a DX-call /
 * serial / Log row, the F1..F8 macro chips, and the TX field with Send.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OperateScreen(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    val listener = mainViewModel.jttySignalListener
    val transmitter = mainViewModel.jttyTransmitter

    val messages by listener.messages.observeAsState(emptyList())
    val isTransmitting by transmitter.isTransmitting.observeAsState(false)
    val isTuning by mainViewModel.tuneOperator.mutableIsTuning.observeAsState(false)

    var txText by rememberSaveable { mutableStateOf("") }
    var dxCall by rememberSaveable { mutableStateOf("") }
    var serial by rememberSaveable { mutableIntStateOf(GeneralVariables.jttySerial) }
    var editMacroIndex by remember { mutableStateOf<Int?>(null) }
    // Bumped whenever a macro is edited so the chip row re-reads the templates.
    var macroVersion by remember { mutableIntStateOf(0) }

    val myCall = GeneralVariables.myCallsign.orEmpty()

    // Validate as the operator types: frame count or the reason it can't be sent.
    val check = remember(txText, GeneralVariables.jttyProfile) { transmitter.check(txText) }
    val canSend = check.first == JttyTransmitter.Reject.NONE && !isTransmitting && !isTuning
    val framesFmt = stringResource(R.string.operate_frames)
    val hint = txHint(check.first, check.second, { context.getString(it) }) { n, secs ->
        String.format(Locale.US, framesFmt, n, secs)
    }

    fun send(text: String) {
        if (myCall.isBlank()) {
            Toast.makeText(context, R.string.operate_set_callsign_first, Toast.LENGTH_SHORT).show()
            return
        }
        when (transmitter.send(text)) {
            JttyTransmitter.Reject.NONE -> txText = ""
            JttyTransmitter.Reject.TOO_LONG ->
                Toast.makeText(context, R.string.operate_too_long, Toast.LENGTH_SHORT).show()
            JttyTransmitter.Reject.UNPACKABLE ->
                Toast.makeText(context, R.string.operate_unpackable, Toast.LENGTH_SHORT).show()
            JttyTransmitter.Reject.TUNE_ACTIVE ->
                Toast.makeText(context, R.string.tune_blocked_tx, Toast.LENGTH_SHORT).show()
            else -> Unit
        }
    }

    fun logQso() {
        if (myCall.isBlank()) {
            Toast.makeText(context, R.string.operate_set_callsign_first, Toast.LENGTH_SHORT).show()
            return
        }
        val dx = dxCall.trim().uppercase(Locale.US)
        if (dx.isEmpty()) return
        val record = buildJttyQsoRecord(
            nowMillis = UtcTimer.getSystemTime(),
            myCall = myCall,
            myGrid = GeneralVariables.getMyMaidenheadGrid().orEmpty(),
            dxCall = dx,
            sentRst = 599,
            receivedRst = 599,
            bandHz = GeneralVariables.band,
            offsetHz = GeneralVariables.getBaseFrequency().toInt(),
        )
        mainViewModel.logQso(record)
        serial = nextSerial(serial)
        GeneralVariables.jttySerial = serial
        mainViewModel.databaseOpr.writeConfig("jttySerial", serial.toString(), null)
        Toast.makeText(context, context.getString(R.string.operate_qso_logged, dx), Toast.LENGTH_SHORT).show()
        dxCall = ""
    }

    // -- Macro editor --
    editMacroIndex?.let { index ->
        MacroEditDialog(
            index = index,
            initial = JttyMacros.templateAt(index, GeneralVariables.jttyMacros),
            onDismiss = { editMacroIndex = null },
            onReset = {
                GeneralVariables.jttyMacros[index] = null
                mainViewModel.databaseOpr.writeConfig("jttyMacro${index + 1}", "", null)
                macroVersion++
                editMacroIndex = null
            },
            onSave = { template ->
                GeneralVariables.jttyMacros[index] = template
                mainViewModel.databaseOpr.writeConfig("jttyMacro${index + 1}", template, null)
                macroVersion++
                editMacroIndex = null
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgApp)) {
        TopBar(title = stringResource(R.string.operate_title)) {
            Text(
                text = stringResource(R.string.operate_clear),
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { mainViewModel.clearDecodes() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // ---- Receive text ----
        ReceivePane(
            messages = messages,
            myCall = myCall,
            onPickCall = { dxCall = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        // ---- Keyboard ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // DX call · serial · Log
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactField(
                    value = dxCall,
                    onValueChange = { dxCall = it.uppercase(Locale.US).filter { c -> c.isLetterOrDigit() || c == '/' } },
                    label = stringResource(R.string.operate_dx_call),
                    placeholder = stringResource(R.string.operate_dx_call_hint),
                    modifier = Modifier.weight(1.2f),
                )
                SerialStepper(
                    serial = serial,
                    onChange = { s ->
                        serial = s
                        GeneralVariables.jttySerial = s
                        mainViewModel.databaseOpr.writeConfig("jttySerial", s.toString(), null)
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = stringResource(R.string.operate_log_qso),
                    enabled = dxCall.isNotBlank(),
                    onClick = { logQso() },
                    modifier = Modifier.weight(0.8f),
                )
            }

            // Macro chips F1..F8, two rows of four. Tap sends; long-press edits.
            key(macroVersion) {
                MacroRows(
                    dxCall = dxCall,
                    enabled = !isTransmitting && !isTuning,
                    onSend = { index ->
                        val template = JttyMacros.templateAt(index, GeneralVariables.jttyMacros)
                        send(JttyMacros.expand(template, myCall, dxCall, serial))
                    },
                    onEdit = { editMacroIndex = it },
                )
            }

            // TX text + Send
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = txText,
                    onValueChange = { txText = it.take(JttyTransmitter.MAX_TEXT + 20) },
                    placeholder = { Text(stringResource(R.string.operate_tx_hint), color = TextFaint, fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = GeistMonoFamily, fontSize = 14.sp, color = TextPrimary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) send(txText) }),
                    colors = fieldColors(),
                    supportingText = hint?.let { h ->
                        {
                            Text(
                                text = h,
                                color = if (check.first == JttyTransmitter.Reject.NONE) TextFaint else StatusBad,
                                fontSize = 10.sp,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = if (isTransmitting) stringResource(R.string.operate_stop) else stringResource(R.string.operate_send),
                    enabled = canSend || isTransmitting,
                    primary = true,
                    danger = isTransmitting,
                    onClick = { if (isTransmitting) transmitter.cancel() else send(txText) },
                    modifier = Modifier.width(84.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Receive pane
// ---------------------------------------------------------------------------

@Composable
private fun ReceivePane(
    messages: List<JttyMessage>,
    myCall: String,
    onPickCall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Follow the tail unless the operator has scrolled up to read history.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty() && atBottom) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(modifier = modifier.background(BgApp)) {
        if (messages.isEmpty()) {
            Text(
                text = stringResource(R.string.operate_rx_empty),
                color = TextFaint,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                ReceiveRow(
                    message = m,
                    myCall = myCall,
                    onPickCall = onPickCall,
                )
            }
        }
    }
}

/** A single received line: `HH:MM:SS  1500  -12  TEXT`, tappable to pick the DX call. */
@Composable
internal fun ReceiveRow(message: JttyMessage, myCall: String, onPickCall: (String) -> Unit) {
    val t = message.utcMillis / 1000
    val clock = String.format(Locale.US, "%02d:%02d:%02d", (t / 3600) % 24, (t % 3600) / 60, t % 60)
    val mentionsMe = myCall.isNotBlank() && message.text.uppercase(Locale.US).split(' ').contains(myCall.uppercase(Locale.US))
    val textColor = when {
        message.isOwnTx -> Accent
        mentionsMe -> Target
        message.text.startsWith("CQ ") -> StatusCq
        else -> TextPrimary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { JttyMessage.otherCallsignIn(message.text, myCall)?.let(onPickCall) }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = clock,
            color = TextFaint,
            fontFamily = GeistMonoFamily,
            fontSize = 11.sp,
        )
        Text(
            text = String.format(Locale.US, "%4.0f", message.freqHz),
            color = TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 11.sp,
        )
        Text(
            text = if (message.isOwnTx) stringResource(R.string.operate_own_tx)
            else String.format(Locale.US, "%+3.0f", message.snrDb),
            color = if (message.isOwnTx) Accent else TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 11.sp,
        )
        Text(
            text = buildString {
                append(message.text)
                if (message.flushed) append(" ").append(stringResource(R.string.operate_incomplete))
                else if (!message.complete) append(" …")
            },
            color = textColor,
            fontFamily = GeistMonoFamily,
            fontSize = 13.sp,
            fontWeight = if (mentionsMe) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Keyboard widgets
// ---------------------------------------------------------------------------

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Accent,
    focusedBorderColor = Accent,
    unfocusedBorderColor = BorderStrong,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextMuted,
)

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        placeholder = { Text(placeholder, color = TextFaint, fontSize = 12.sp) },
        singleLine = true,
        textStyle = TextStyle(fontFamily = GeistMonoFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        colors = fieldColors(),
        modifier = modifier,
    )
}

@Composable
private fun SerialStepper(serial: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface3)
            .border(1.dp, Border, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−") { onChange((serial - 1).coerceAtLeast(1)) }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.operate_serial),
                color = TextFaint,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
            )
            Text(
                text = String.format(Locale.US, "%03d", serial),
                color = TextPrimary,
                fontFamily = GeistMonoFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        StepButton("+") { onChange(nextSerial(serial)) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(32.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = TextMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
    }
}

@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
) {
    val bg = when {
        danger -> StatusBad
        !enabled -> BgSurface3
        primary -> Accent
        else -> AccentSoft
    }
    val fg = when {
        danger -> androidx.compose.ui.graphics.Color.White
        !enabled -> TextFaint
        primary -> BgApp
        else -> Accent
    }
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = InterFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MacroRows(
    dxCall: String,
    enabled: Boolean,
    onSend: (Int) -> Unit,
    onEdit: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (col in 0 until 4) {
                    val index = row * 4 + col
                    val template = JttyMacros.templateAt(index, GeneralVariables.jttyMacros)
                    val needsDx = JttyMacros.needsDxCall(template) && dxCall.isBlank()
                    val chipEnabled = enabled && !needsDx
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (chipEnabled) BgSurface3 else BgSurface3.copy(alpha = 0.5f))
                            .border(1.dp, Border, RoundedCornerShape(8.dp))
                            .combinedClickable(
                                enabled = enabled,
                                onClick = { if (chipEnabled) onSend(index) },
                                onLongClick = { onEdit(index) },
                            )
                            .padding(horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "F${index + 1}",
                            color = TextFaint,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                        )
                        Text(
                            text = JttyMacros.LABELS.getOrElse(index) { "F${index + 1}" },
                            color = if (chipEnabled) TextPrimary else TextFaint,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = InterFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MacroEditDialog(
    index: Int,
    initial: String,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.operate_macro_edit_title, index + 1),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Text(
                    text = stringResource(R.string.operate_macro_help),
                    color = TextMuted,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.uppercase(Locale.US) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = GeistMonoFamily, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.operate_macro_reset), color = TextMuted)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel), color = TextMuted)
                    }
                    TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) {
                        Text(stringResource(R.string.action_save), color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
