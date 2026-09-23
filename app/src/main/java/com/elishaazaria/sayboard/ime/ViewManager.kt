package com.elishaazaria.sayboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalContentAlpha
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.elishaazaria.sayboard.AppPrefs
import com.elishaazaria.sayboard.Constants
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.data.ThemeMode
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import com.elishaazaria.sayboard.sayboardPreferenceModel
import com.elishaazaria.sayboard.theme.Shapes
import com.elishaazaria.sayboard.ui.utils.MyIconButton
import com.elishaazaria.sayboard.utils.AudioDevices
import com.elishaazaria.sayboard.utils.Key
import com.elishaazaria.sayboard.utils.MaxCustomKeysPerRow
import com.elishaazaria.sayboard.utils.describe
import com.elishaazaria.sayboard.utils.toIcon
import dev.patrickgold.jetpref.datastore.model.observeAsState as jetPrefObserveAsState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Five-row key panel, perfectly symmetric, with a slim top strip + bottom gap.
 * Portrait: top strip, tall mic pill, two 5-key nav rows, custom row, gap.
 * Landscape: model + keyboard buttons above a rectangular mic pill on the
 * left; custom row (if enabled) on top, then the two nav rows, on the right.
 * Row heights are weight-based so the panel always fits exactly and adding or
 * removing the custom row recalculates every row automatically.
 *
 * The mic pill is the dominant action; its label doubles as the status text.
 * Icons/paddings scale with the keyboard height via panelScale() while label
 * sizes stay fixed. Theme/mode/height prefs are observed so changes apply
 * live without restarting.
 */
@SuppressLint("ViewConstructor")
class ViewManager(private val ime: Context) : AbstractComposeView(ime),
    Observer<RecognizerState> {
    private val prefs by sayboardPreferenceModel()
    val stateLD = MutableLiveData(STATE_INITIAL)
    val errorMessageLD = MutableLiveData(R.string.mic_info_error)
    private var listener: Listener? = null
    val recognizerNameLD = MutableLiveData("")
    val enterActionLD = MutableLiveData(EditorInfo.IME_ACTION_UNSPECIFIED)

    val recordDevice: MutableLiveData<AudioDeviceInfo?> = MutableLiveData()

    private var devices: List<AudioDeviceInfo> = listOf()

    /**
     * View-level haptics (no Compose-version dependency): key tap buzz and
     * long-press buzz, with a pre-Oreo fallback. Honors the system
     * touch-feedback setting via the framework path.
     */
    private fun keyboardTap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } else {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun longPressBuzz() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        val stateS = stateLD.observeAsState()
        val recognizerNameS = recognizerNameLD.observeAsState(initial = "")
        // Observed (not .get()) so height/theme changes apply live, no restart
        val heightPortrait by prefs.keyboardHeightPortrait.jetPrefObserveAsState()
        val heightLandscape by prefs.keyboardHeightLandscape.jetPrefObserveAsState()
        val landscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val height =
            (LocalConfiguration.current.screenHeightDp * if (landscape) heightLandscape else heightPortrait).toInt().dp
        var showDevicesPopup by remember { mutableStateOf(false) }
        val recordDeviceS by recordDevice.observeAsState()

        IMETheme(prefs) {
            CompositionLocalProvider(
                LocalContentAlpha provides 1f
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .background(MaterialTheme.colors.background)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Portrait keeps the full strip; landscape moves model
                        // and keyboard above the mic pill instead
                        if (!landscape) {
                            StatusBarRow(
                                recognizerNameS = recognizerNameS,
                                recordDeviceS = recordDeviceS,
                                onBack = { listener?.backClicked() },
                                onModel = { listener?.modelClicked() },
                                onDevice = {
                                    devices = AudioDevices.validAudioDevices(ime)
                                    showDevicesPopup = true
                                },
                                onSettings = { listener?.settingsClicked() }
                            )
                        }

                        MainRow(
                            modifier = Modifier.weight(1f),
                            state = stateS.value ?: STATE_INITIAL,
                            onMic = { listener?.micClick() },
                            onMicLong = { listener?.micLongClick() },
                            onButton = { text -> listener?.buttonClicked(text) },
                            onKey = { keyCode -> listener?.keyClicked(keyCode) },
                            onReturn = { listener?.returnClicked() },
                            onSelectAll = { listener?.selectAllClicked() },
                            onCopy = { listener?.copyClicked() },
                            onPaste = { listener?.pasteClicked() }
                        )
                    }

                    if (showDevicesPopup) {
                        DevicesPopup(
                            devices = devices,
                            current = recordDeviceS,
                            onPick = {
                                showDevicesPopup = false
                                recordDevice.postValue(it)
                            },
                            onDismiss = { showDevicesPopup = false }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusBarRow(
        recognizerNameS: State<String>,
        recordDeviceS: AudioDeviceInfo?,
        onBack: () -> Unit,
        onModel: () -> Unit,
        onDevice: () -> Unit,
        onSettings: () -> Unit
    ) {
        val scale = panelScale()
        val contentColor = MaterialTheme.colors.onBackground
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
        ) {
            // settings: hugging the top-start corner for maximum model-name room
            Box(
                modifier = Modifier.wrapContentWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                // settings: rarely used, kept small and out of the way in the top-start corner
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(id = R.string.desc_settings),
                        tint = contentColor,
                        modifier = Modifier.size(24.dp * scale)
                    )
                }
            }

            // model / language switcher: globe pinned to the slot's start,
            // name takes the remaining width centered. The globe never moves;
            // only the name text changes.
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onModel)
                        .padding(vertical = 4.dp)
                ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = stringResource(id = R.string.desc_model),
                            tint = contentColor,
                            modifier = Modifier.size(24.dp * scale)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = recognizerNameS.value,
                        fontSize = 12.sp,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            // audio input device picker
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onDevice) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = recordDeviceS?.toIcon() ?: Icons.Default.PhoneAndroid,
                            contentDescription = stringResource(id = R.string.desc_audio_device),
                            tint = contentColor,
                            modifier = Modifier.size(24.dp * scale)
                        )
                        recordDeviceS?.let {
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = it.describe(),
                                fontSize = 9.sp,
                                color = contentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // switch keyboards: hugging the top-end corner
            Box(
                modifier = Modifier.wrapContentWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = stringResource(id = R.string.desc_switch_keyboard),
                        tint = contentColor,
                        modifier = Modifier.size(24.dp * scale)
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterialApi::class)
    @Composable
    private fun DevicesPopup(
        devices: List<AudioDeviceInfo>,
        current: AudioDeviceInfo?,
        onPick: (AudioDeviceInfo) -> Unit,
        onDismiss: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background.copy(0.5f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize(0.8f)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        stringResource(id = R.string.mic_audio_device_title),
                        modifier = Modifier.padding(10.dp)
                    )

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(devices) { device ->
                            // Highlight the active input device (id compare
                            // needs API 28+; older releases show no marker).
                            val isCurrent =
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                                    current != null && device.id == current.id
                            Card(
                                onClick = { onPick(device) },
                                modifier = Modifier
                                    .fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .background(
                                            if (isCurrent) {
                                                MaterialTheme.colors.primary.copy(0.25f)
                                            } else {
                                                MaterialTheme.colors.onSurface.copy(0.2f)
                                            }
                                        )
                                        .padding(10.dp)
                                ) {
                                    Icon(
                                        imageVector = device.toIcon(),
                                        contentDescription = stringResource(id = R.string.desc_audio_device),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(device.describe())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Scale factor for icons/paddings so they grow with the user-configured
     * keyboard height. 1.0 == 340dp reference panel height. Row heights
     * themselves are weight-based (see MainRow) and always fit exactly.
     */
    @Composable
    private fun panelScale(): Float {
        val heightDp = LocalConfiguration.current.screenHeightDp * when (LocalConfiguration.current.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> prefs.keyboardHeightLandscape.get()
            else -> prefs.keyboardHeightPortrait.get()
        }
        return (heightDp / 340f).coerceIn(0.6f, 1.5f)
    }

    /**
     * First navigation row (shared by portrait and landscape):
     * space, copy, up, paste, backspace. Space and up repeat while held.
     */
    @Composable
    private fun RowScope.NavRow1(
        onKey: (Int) -> Unit,
        onCopy: () -> Unit,
        onPaste: () -> Unit
    ) {
        val scale = panelScale()
        RepeatKeyCap(onFire = { listener?.spaceClicked() }) {
            Icon(
                imageVector = Icons.Default.SpaceBar,
                contentDescription = stringResource(id = R.string.desc_space),
                modifier = Modifier
                    .padding(8.dp * scale)
                    .size(24.dp * scale)
            )
        }
        KeyCap(onClick = onCopy) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(id = R.string.desc_copy),
                modifier = Modifier
                    .padding(8.dp * scale)
                    .size(24.dp * scale)
            )
        }
        RepeatKeyCap(onFire = { onKey(KeyEvent.KEYCODE_DPAD_UP) }) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(id = R.string.desc_arrow_up),
                modifier = Modifier
                    .padding(8.dp * scale)
                    .size(24.dp * scale)
            )
        }
        KeyCap(onClick = onPaste) {
            Icon(
                imageVector = Icons.Default.ContentPaste,
                contentDescription = stringResource(id = R.string.desc_paste),
                modifier = Modifier
                    .padding(8.dp * scale)
                    .size(24.dp * scale)
            )
        }
        BackspaceKey()
    }

    /**
     * Backspace with Sayboard's hold-to-repeat + swipe-left gestures.
     * Fills its slot exactly like the other keys (same padding).
     */
    @Composable
    private fun RowScope.BackspaceKey() {
        val scale = panelScale()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        val held = java.util.concurrent.atomic.AtomicBoolean(true)
                        coroutineScope {
                            val startMs = android.os.SystemClock.uptimeMillis()
                            val repeatJob = launch {
                                try {
                                    delay(Constants.BackspaceRepeatStartDelay)
                                    while (held.get() &&
                                        android.os.SystemClock.uptimeMillis() - startMs < MAX_REPEAT_MS
                                    ) {
                                        listener?.backspaceClicked()
                                        delay(Constants.BackspaceRepeatDelay)
                                    }
                                } finally {
                                    held.set(false)
                                }
                            }
                            launch {
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    held.set(false)
                                    repeatJob.cancel()
                                }
                            }
                        }
                    }, onTap = {
                        keyboardTap()
                        listener?.backspaceClicked()
                    })
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragStart = {
                        listener?.backspaceTouchStart(it)
                    }, onDragCancel = {
                        listener?.backspaceTouchEnd()
                    }, onDragEnd = {
                        listener?.backspaceTouchEnd()
                    }, onHorizontalDrag = { change, amount ->
                        listener?.backspaceTouched(change, amount)
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            KeyFace(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.82f)
            ) {
                Icon(
                imageVector = Icons.Default.Backspace,
                contentDescription = stringResource(id = R.string.desc_backspace),
                    modifier = Modifier
                        .padding(8.dp * scale)
                        .size(24.dp * scale)
                )
            }
        }
    }

    /**
     * Second navigation row (shared by portrait and landscape):
     * select all, left, down, right, enter. Arrows repeat while held.
     */
    @Composable
    private fun RowScope.NavRow2(
        onKey: (Int) -> Unit,
        onReturn: () -> Unit,
        onSelectAll: () -> Unit
    ) {
        val scale = panelScale()
        KeyCap(onClick = onSelectAll) {
            Icon(
                imageVector = Icons.Default.SelectAll,
                contentDescription = stringResource(id = R.string.desc_select_all),
                modifier = Modifier
                    .padding(8.dp * scale)
                    .size(24.dp * scale)
            )
        }
        RepeatKeyCap(onFire = { onKey(KeyEvent.KEYCODE_DPAD_LEFT) }) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = stringResource(id = R.string.desc_arrow_left),
                modifier = Modifier
                    .padding(8.dp * scale)
                    .size(24.dp * scale)
            )
        }
        RepeatKeyCap(onFire = { onKey(KeyEvent.KEYCODE_DPAD_DOWN) }) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(id = R.string.desc_arrow_down),
                modifier = Modifier
                    .padding(8.dp * scale)
                    .size(24.dp * scale)
            )
        }
        RepeatKeyCap(onFire = { onKey(KeyEvent.KEYCODE_DPAD_RIGHT) }) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = stringResource(id = R.string.desc_arrow_right),
                modifier = Modifier
                    .padding(8.dp * scale)
                    .size(24.dp * scale)
            )
        }
        // enter/return key, same key-face background as the other
        // buttons; icon adapts to the editor's IME action.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            KeyFace(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.82f)
                    .clickable(onClick = {
                        keyboardTap()
                        onReturn()
                    })
            ) {
            val enterAction by enterActionLD.observeAsState()
            Icon(
                imageVector = when (enterAction) {
                    EditorInfo.IME_ACTION_GO -> Icons.Default.ArrowRightAlt
                    EditorInfo.IME_ACTION_SEARCH -> Icons.Default.Search
                    EditorInfo.IME_ACTION_SEND -> Icons.Default.Send
                    EditorInfo.IME_ACTION_NEXT -> Icons.Default.NavigateNext
                    EditorInfo.IME_ACTION_PREVIOUS -> Icons.Default.NavigateBefore
                    else -> Icons.Default.KeyboardReturn
                },
                contentDescription = stringResource(id = R.string.desc_enter),
                modifier = Modifier.size(24.dp * scale)
            )
            }
        }
    }

    @Composable
    private fun MainRow(
        modifier: Modifier = Modifier,
        state: Int,
        onMic: () -> Unit,
        onMicLong: () -> Unit,
        onButton: (String) -> Unit,
        onKey: (Int) -> Unit,
        onReturn: () -> Unit,
        onSelectAll: () -> Unit,
        onCopy: () -> Unit,
        onPaste: () -> Unit
    ) {
        val customKeys by prefs.keyboardKeysCustom.jetPrefObserveAsState()
        val scale = panelScale()
        val landscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (landscape) {
            // Landscape: language name sits between the model and keyboard
            // buttons above a rectangular mic pill on the left; the five key
            // buttons compress into less width so the pill grows both ways.
            // Custom row (if enabled) on top, then the two nav rows, with
            // breathing gaps below the pill and the rows alike.
            val recognizerName by recognizerNameLD.observeAsState(initial = "")
            val contentColor = MaterialTheme.colors.onBackground
            BoxWithConstraints(
                modifier = modifier
                    .fillMaxWidth()
            ) {
                val panelWidth = maxWidth
                Row(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(panelWidth * 0.36f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Compact clickable icons (no 48dp IconButton
                            // frames) so the model name gets maximum width.
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = stringResource(id = R.string.desc_model),
                                tint = contentColor,
                                modifier = Modifier
                                    .clickable(onClick = { listener?.modelClicked() })
                                    .padding(4.dp)
                                    .size(22.dp)
                            )
                            Text(
                                text = recognizerName,
                                fontSize = 10.sp,
                                color = contentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 2.dp)
                            )
                            Icon(
                                imageVector = Icons.Default.Keyboard,
                                contentDescription = stringResource(id = R.string.desc_switch_keyboard),
                                tint = contentColor,
                                modifier = Modifier
                                    .clickable(onClick = { listener?.backClicked() })
                                    .padding(4.dp)
                                    .size(22.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            MicPill(
                                state = state,
                                onClick = onMic,
                                onLongClick = onMicLong,
                                rectangular = true,
                                modifier = Modifier.fillMaxWidth(0.94f)
                            )
                        }
                        Spacer(modifier = Modifier.weight(0.5f))
                    }
                val showCustomLandscape by prefs.keyboardShowCustomRowLandscape.jetPrefObserveAsState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (showCustomLandscape) {
                        CustomKeyRow(keys = customKeys, onButton = onButton)
                    }
                    KeyRow { NavRow1(onKey, onCopy, onPaste) }
                    KeyRow { NavRow2(onKey, onReturn, onSelectAll) }
                    // breathing room below the rows (half a key-row unit)
                    Spacer(modifier = Modifier.weight(0.5f))
                }
                }
            }
            return
        }

        val showCustomPortrait by prefs.keyboardShowCustomRowPortrait.jetPrefObserveAsState()

        // Weight-based: mic row = 1.5 units, every key row = 1 unit, bottom
        // gap = 1 unit. Adding/removing a custom row automatically
        // recalculates every row height; the total always fits exactly.
        Column(
            modifier = modifier
                .fillMaxWidth()
        ) {
            // Row 1: the mic pill, exactly as wide as the middle three keys
            // below and 1.5x their height. Tap toggles listening.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.5f),
                contentAlignment = Alignment.Center
            ) {
                val pillWidth =
                    (maxWidth - 16.dp * scale - 32.dp * scale) * 3f / 5f + 16.dp * scale
                MicPill(
                    state = state,
                    onClick = onMic,
                    onLongClick = onMicLong,
                    modifier = Modifier.width(pillWidth)
                )
            }

            // Row 2: space, copy, up, paste, backspace
            KeyRow { NavRow1(onKey, onCopy, onPaste) }

            // Row 3: select all, left, down, right, enter
            KeyRow { NavRow2(onKey, onReturn, onSelectAll) }

            // Single user-configurable custom row, hidden when empty or toggled off.
            if (showCustomPortrait) {
                CustomKeyRow(keys = customKeys, onButton = onButton)
            }

            // Bottom gap: exactly one key-row height, like the rows above
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    /**
     * One symmetric key row: five equal keys, evenly spaced, one weight unit tall.
     */
    @Composable
    private fun ColumnScope.KeyRow(
        content: @Composable RowScope.() -> Unit
    ) {
        val scale = panelScale()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp * scale),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp * scale),
            content = content
        )
    }

    /**
     * A single fixed key cap: fills its slot, but the visible cap is a
     * fraction of it so keys never touch — gaps stay fixed, cap size scales.
     */
    @Composable
    private fun RowScope.KeyCap(
        onClick: () -> Unit,
        content: @Composable () -> Unit
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            KeyFace(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.82f)
                    .clickable(onClick = {
                        keyboardTap()
                        onClick()
                    })
            ) {
                content()
            }
        }
    }

    /**
     * A user-configurable custom row: up to 5 keys spanning the full row
     * width in equal shares, exactly like the fixed rows. Hidden when empty.
     * Tap inserts the primary text; long-press inserts the secondary text
     * (shown miniature in the key's top-right corner when set).
     */
    @Composable
    private fun ColumnScope.CustomKeyRow(
        keys: List<Key>,
        onButton: (String) -> Unit
    ) {
        if (keys.isEmpty()) return
        val scale = panelScale()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp * scale),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp * scale)
        ) {
            for (key in keys.take(MaxCustomKeysPerRow)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(0.82f)
                ) {
                    KeyFace(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(key) {
                                detectTapGestures(
                                    onTap = {
                                        keyboardTap()
                                        onButton(key.text)
                                    },
                                    onLongPress = {
                                        longPressBuzz()
                                        onButton(
                                            key.longText.ifBlank { key.text }
                                        )
                                    }
                                )
                            }
                    ) {
                        Text(
                            text = key.label,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp * scale, vertical = 8.dp * scale)
                        )
                    }
                    if (key.longLabel.isNotBlank()) {
                        Text(
                            text = key.longLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colors.onSurface,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 2.dp * scale, end = 6.dp * scale)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MicPill(
        state: Int,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        modifier: Modifier = Modifier,
        rectangular: Boolean = false
    ) {
        val container = when (state) {
            STATE_LISTENING, STATE_LISTENING_OFFLINE -> MaterialTheme.colors.error
            else -> MaterialTheme.colors.primary
        }
        val content = when (state) {
            STATE_LISTENING, STATE_LISTENING_OFFLINE -> MaterialTheme.colors.onError
            else -> MaterialTheme.colors.onPrimary
        }
        val icon = when (state) {
            STATE_INITIAL, STATE_LOADING, STATE_TRANSCRIBING -> Icons.Default.SettingsVoice
            STATE_READY, STATE_PAUSED -> Icons.Default.MicNone
            STATE_LISTENING, STATE_LISTENING_OFFLINE -> Icons.Default.Mic
            else -> Icons.Default.MicOff
        }
        val errorRes by errorMessageLD.observeAsState(R.string.mic_info_error)
        val label = when (state) {
            STATE_INITIAL, STATE_LOADING -> stringResource(id = R.string.mic_info_preparing)
            STATE_READY, STATE_PAUSED -> stringResource(id = R.string.mic_info_ready)
            STATE_LISTENING -> stringResource(id = R.string.mic_info_recording)
            STATE_LISTENING_OFFLINE -> stringResource(id = R.string.mic_info_tap_to_transcribe)
            STATE_TRANSCRIBING -> stringResource(id = R.string.mic_info_transcribing)
            else -> stringResource(id = errorRes)
        }

        MyIconButton(
            onClick = {
                keyboardTap()
                onClick()
            },
            onLongClick = onLongClick,
            modifier = modifier
                .background(
                    container,
                    if (rectangular) RoundedCornerShape(12.dp) else RoundedCornerShape(percent = 50)
                )
                .fillMaxHeight(if (rectangular) 0.94f else 0.9f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp * panelScale(), vertical = 12.dp * panelScale())
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = content
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    color = content,
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }
        }
    }

    /**
     * A fixed key cap that fires once on tap and keeps firing while held.
     * I-19: repeats are bounded (~2s max), guarded by an atomic held flag,
     * and always cancelled in a finally block. Same slot geometry as KeyCap.
     * Used for space and the cursor arrows.
     */
    @Composable
    private fun RowScope.RepeatKeyCap(
        onFire: () -> Unit,
        content: @Composable () -> Unit
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            KeyFace(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.82f)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            coroutineScope {
                                val held = java.util.concurrent.atomic.AtomicBoolean(true)
                                val startMs = android.os.SystemClock.uptimeMillis()
                                val repeat = launch {
                                    try {
                                        delay(400)
                                        while (held.get() &&
                                            android.os.SystemClock.uptimeMillis() - startMs < MAX_REPEAT_MS
                                        ) {
                                            onFire()
                                            delay(80)
                                        }
                                    } finally {
                                        held.set(false)
                                    }
                                }
                                keyboardTap()
                                onFire()
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    held.set(false)
                                    repeat.cancel()
                                }
                            }
                        })
                    }
            ) {
                content()
            }
        }
    }

    /**
     * A flat rounded-rect key face in the functional-key
     * color (theme surface), glyphs in the contrasting on-surface color.
     * Used for space, backspace, and the custom key caps.
     */
    @Composable
    private fun KeyFace(
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit
    ) {
        val borders by prefs.uiKeyBorders.jetPrefObserveAsState()
        val faceShape = RoundedCornerShape(8.dp)
        var faceModifier = modifier.background(
            MaterialTheme.colors.surface,
            faceShape
        )
        if (borders) {
            faceModifier = faceModifier.border(
                1.dp,
                MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                faceShape
            )
        }
        Box(
            modifier = faceModifier,
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(
                LocalContentAlpha provides 1f,
                LocalContentColor provides MaterialTheme.colors.onSurface
            ) {
                content()
            }
        }
    }

    override fun onChanged(value: RecognizerState) {
        // postValue: recognizer states can arrive off the main thread.
        when (value) {
            RecognizerState.CLOSED, RecognizerState.NONE -> stateLD.postValue(STATE_INITIAL)

            RecognizerState.LOADING -> stateLD.postValue(STATE_LOADING)
            RecognizerState.READY -> stateLD.postValue(STATE_READY)
            RecognizerState.IN_RAM -> stateLD.postValue(STATE_PAUSED)
            RecognizerState.ERROR -> stateLD.postValue(STATE_ERROR)
        }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    interface Listener {
        fun micClick()
        fun micLongClick(): Boolean
        fun backClicked()
        fun backspaceClicked()
        fun backspaceTouchStart(offset: Offset)
        fun backspaceTouched(change: PointerInputChange, dragAmount: Float)
        fun backspaceTouchEnd()
        fun spaceClicked()
        fun returnClicked()
        fun modelClicked()
        fun settingsClicked()
        fun buttonClicked(text: String)
        fun deviceChanged(device: AudioDeviceInfo)
        fun keyClicked(keyCode: Int)
        fun selectAllClicked()
        fun copyClicked()
        fun pasteClicked()
    }

    companion object {
        const val STATE_INITIAL = 0
        const val STATE_LOADING = 1
        const val STATE_READY = 2 // model loaded, ready to start
        const val STATE_LISTENING = 3
        const val STATE_PAUSED = 4
        const val STATE_ERROR = 5
        // Recording with a record-then-transcribe source (Whisper/Parakeet):
        // second mic tap stops recording and transcribes.
        const val STATE_LISTENING_OFFLINE = 6
        // A record-then-transcribe source is decoding the take.
        const val STATE_TRANSCRIBING = 7
        /** I-19: max hold-to-repeat duration. */
        private const val MAX_REPEAT_MS = 2000L
    }
}

/**
 * Keyboard theme: soft surface-container panel (never pure
 * black), blue primary mic pill (red while listening), surface-variant key
 * faces with contrasting glyphs. Day/night background + foreground stay
 * user-configurable (see UI settings); the remaining roles are derived for
 * contrast in each mode, with optional Material You foreground.
 */
@Composable
fun IMETheme(
    prefs: AppPrefs,
    content: @Composable () -> Unit
) {
    // Observed (not .get()) so theme changes apply live, no restart needed
    val darkMode by prefs.uiThemeMode.jetPrefObserveAsState()
    val dynamicColors by prefs.uiDynamicColors.jetPrefObserveAsState()
    val accentPref by prefs.uiAccent.jetPrefObserveAsState()
    val backgroundPref by prefs.uiKeyboardBackground.jetPrefObserveAsState()
    val dark = when (darkMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val bgColor = parseHexColor(
        backgroundPref.takeIf { it != "default" }
    ) ?: Color(if (dark) 0xFF1D2024 else 0xFFEDEDF4)
    val onBgColor = if (backgroundPref != "default") {
        contrastingOn(bgColor)
    } else {
        Color(if (dark) 0xFFE2E2E9 else 0xFF191C20)
    }
    val accentColor = parseHexColor(
        accentPref.takeIf { it != "system" }
    )
    val primary = accentColor
        ?: if (dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            colorResource(id = if (dark) R.color.materialYouForegroundDark else R.color.materialYouForegroundLight)
        } else {
            Color(if (dark) 0xFFA9C7FF else 0xFF405F90)
        }
    val onPrimaryColor = if (accentColor != null) {
        contrastingOn(primary)
    } else {
        Color(if (dark) 0xFF08305F else 0xFFFFFFFF)
    }

    val colors = if (dark) {
        darkColors(
            primary = primary,
            onPrimary = onPrimaryColor,
            background = bgColor,
            onBackground = onBgColor,
            surface = Color(0xFF43474E),
            onSurface = Color(0xFFD9E3F9),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
        )
    } else {
        lightColors(
            primary = primary,
            onPrimary = onPrimaryColor,
            background = bgColor,
            onBackground = onBgColor,
            surface = Color(0xFFE0E2EC),
            onSurface = Color(0xFF3E4758),
            error = Color(0xFFBA1A1A),
            onError = Color.White,
        )
    }

    MaterialTheme(
        colors = colors,
        shapes = Shapes,
        content = content,
    )
}

/** Parse a #RRGGBB or #AARRGGBB string to a [Color], null when invalid. */
fun parseHexColor(spec: String?): Color? {
    if (spec.isNullOrBlank()) return null
    return try {
        val s = spec.trim().removePrefix("#")
        require(s.length == 6 || s.length == 8)
        require(s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        Color(android.graphics.Color.parseColor("#$s"))
    } catch (_: Exception) {
        null
    }
}

/** Near-black on light colors, white on dark ones. */
fun contrastingOn(color: Color): Color {
    val luminance = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    return if (luminance > 0.5f) Color(0xFF191C20) else Color.White
}
