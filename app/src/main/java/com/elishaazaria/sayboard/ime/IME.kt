// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.elishaazaria.sayboard.ime

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.core.app.ActivityCompat
import com.elishaazaria.sayboard.BuildConfig
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.data.KeepScreenAwakeMode
import com.elishaazaria.sayboard.backup.ConfigBackupIO
import com.elishaazaria.sayboard.recognition.ModelManager
import com.elishaazaria.sayboard.recognition.SharedModelManager
import com.elishaazaria.sayboard.services.KeepAlive
import com.elishaazaria.sayboard.utils.AppLog
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.sayboardPreferenceModel
import org.vosk.LibVosk
import org.vosk.LogLevel
import java.util.Locale
import kotlin.math.roundToInt

class IME : InputMethodService(), ModelManager.Listener {
    private val prefs by sayboardPreferenceModel()

    private var hasMicPermission: Boolean = false

    public val lifecycleOwner = IMELifecycleOwner()
    private lateinit var editorInfo: EditorInfo
    private lateinit var viewManager: ViewManager
    private lateinit var modelManager: ModelManager
    private lateinit var actionManager: ActionManager
    private lateinit var textManager: TextManager

    private var currentRecognizerSource: RecognizerSource? = null
    /** Last package seen in onStartInputView: locale apply runs on change only. */
    private var lastInputPackage: String? = null

    /**
     * I-02/I-08 session tag: package + window token snapshotted at
     * onStartInputView. Kept for diagnostics only — results commit
     * unconditionally (pre-audit behavior) so dictation works in every
     * app, including ones that bounce the input view or reset the
     * connection mid-take. See dropReason() + AppLog lines below.
     * The hidden lifecycle rests at ON_STOP (see IMELifecycleOwner.onStop).
     */
    private var sessionPackage: String? = null
    private var sessionToken: IBinder? = null
    private var inputViewShown = false

    /** I-17: onBindInput reload debounce (min 2s, skip when order unchanged). */
    private var lastBindReloadMs = 0L
    private var lastModelsOrderHash = 0


    public var enterAction = EditorInfo.IME_ACTION_UNSPECIFIED
        private set

    /**
     * F2: richness from the LIVE EditorInfo (cached `onStartInputView`
     * snapshot as fallback). The cached `isRichTextEditor` field can
     * disagree with live `isPasswordField()` across an input restart, so
     * every routing decision below uses this live-first read instead.
     * Same `A || (B && C)` shape as the original FlorisBoard-derived check.
     */
    fun isRichTextEditorNow(): Boolean {
        val info = try {
            currentInputEditorInfo
        } catch (_: Exception) {
            null
        } ?: if (::editorInfo.isInitialized) editorInfo else return true
        val t = info.inputType
        return t and InputType.TYPE_MASK_CLASS != EditorInfo.TYPE_NULL ||
            info.initialSelStart >= 0 && info.initialSelEnd >= 0
    }

    /**
     * F2: user-visible notice channel for plain-editor fallback failures.
     * Mirrors refuseVoiceInPassword(): errorMessageLD alone is invisible
     * (ViewManager renders it only in error states), so the error state
     * rides along; the next recognizer state overwrites both, same pattern
     * as the mic-permission and recognizer-error paths.
     */
    fun showEditorNotice(resId: Int) {
        try {
            viewManager.errorMessageLD.postValue(resId)
            viewManager.stateLD.postValue(ViewManager.STATE_ERROR)
        } catch (_: Exception) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        // I-01: counts/ids only, no transcripts or EditorInfo dumps.
        if (BuildConfig.DEBUG) Log.d("IME", "@onCreate")

        AppLog.d("IME", "onCreate")
        KeepAlive.sync(this, prefs.logicKeepAliveService.get())
        lifecycleOwner.onCreate()

        LibVosk.setLogLevel(if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.WARNINGS)

        viewManager = ViewManager(this)
        viewManager.setListener(viewManagerListener)

        actionManager = ActionManager(this, viewManager)

        checkMicrophonePermission()

        modelManager = SharedModelManager.get(this, this)
        modelManager.initializeFirstLocale(prefs.logicListenImmediately.get())

        textManager = TextManager(this, modelManager)

        viewManager.recordDevice.observe(lifecycleOwner) {
            modelManager.recordDevice = it
        }
    }

    /**
     * Called on create and after a configuration change
     */

    override fun onInitializeInterface() {
        if (BuildConfig.DEBUG) Log.d("IME", "@onInitializeInterface")

        checkMicrophonePermission()
    }

    /**
     * Called when switching to a new app (input sink)
     */
    override fun onBindInput() {
        if (BuildConfig.DEBUG) Log.d("IME", "@onBindInput")

        // I-17: debounce reloads (min 2s) and skip when the order is
        // unchanged — focus-driven reloads must stay cheap.
        val now = android.os.SystemClock.uptimeMillis()
        val orderHash = runCatching { prefs.modelsOrder.get().hashCode() }.getOrDefault(0)
        if (now - lastBindReloadMs < 2000 || orderHash == lastModelsOrderHash && lastBindReloadMs != 0L) {
            return
        }
        lastBindReloadMs = now
        lastModelsOrderHash = orderHash
        modelManager.reloadModels()
        modelManager.initializeFirstLocale(prefs.logicListenImmediately.get())
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleOwner.onResume()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // I-08: hidden rests at ON_STOP (observers pause; ViewModels span
        // sessions, LiveData re-delivers on resume — no data loss).
        lifecycleOwner.onStop()
    }

    /**
     * Called when the keyboard is opened (called twice for some reason)
     */
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        if (BuildConfig.DEBUG) Log.d("IME", "@onStartInputView")

        checkMicrophonePermission()
        editorInfo = info
        // I-02: snapshot the session tag for result confirmation.
        sessionPackage = info.packageName
        sessionToken = token
        inputViewShown = true
        enterAction = findEnterAction()
        viewManager.enterActionLD.postValue(enterAction)
        // isRichTextEditorNow() reads live at each use site (F2); no cached copy.
        textManager.onResume()
        setKeepScreenOn(prefs.logicKeepScreenAwake.get() == KeepScreenAwakeMode.WHEN_OPEN)
        actionManager.onStartInputView()
        // Locale apply is per package, not per field: moving between fields
        // of the same app re-fires this (twice, per the note above) and must
        // not re-read prefs, reload models, or toast each time.
        val packageChanged = info.packageName != lastInputPackage
        lastInputPackage = info.packageName
        if (!restarting && packageChanged &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        ) {
            applyLocaleForPackage(info)
        }
    }

    /**
     * Pick the model for this field: the field's reported locale first
     * (rare; most apps do not set one), then the locale last used in this
     * app, so switching from another keyboard for voice input lands on the
     * right language. Announces the pick with a short toast.
     */
    private fun applyLocaleForPackage(info: EditorInfo) {
        val before = modelManager.currentLocale
        var switched = false
        if (prefs.logicAutoSwitchModelByLocale.get()) {
            // hintLocales is API 24+: keep every access lexically inside the guard.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val locales = info.hintLocales
                if (locales != null && !locales.isEmpty) {
                    switched = modelManager.maybeSwitchToLocale(locales[0])
                }
            }
        }
        if (!switched && prefs.logicRememberLocalePerApp.get()) {
            val tag = prefs.localePerApp.get()[info.packageName]
            // I-22: strict BCP-47 shape before trusting a stored tag.
            val remembered = tag
                ?.takeIf { LOCALE_TAG_PATTERN.matches(it) }
                ?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() }
                ?.takeIf { it.language.isNotEmpty() && it.language != "und" }
            if (remembered != null) {
                switched = modelManager.maybeSwitchToLocale(remembered)
            }
        }
        val after = modelManager.currentLocale
        if (switched && after != null && after != before) {
            // Name the model (two English models share a display language).
            val label = modelManager.currentSourceName ?: after.displayLanguage
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * I-09 central password-field helper: inputType variation check.
     * Reads the LIVE EditorInfo (getCurrentInputEditorInfo): the cached
     * onStartInputView snapshot can go stale across input restarts and
     * false-positive on a previous field, silently blocking dictation in
     * a normal field. Voice commit, spacing/capitalization, composing,
     * clipboard and commit-formatting are all skipped in password fields
     * unless the user opted into voice-everywhere (logicAllowVoiceInPassword).
     */
    fun isPasswordField(): Boolean {
        val info = currentInputEditorInfo ?: if (::editorInfo.isInitialized) editorInfo else return false
        val inputType = info.inputType
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    /** I-09 gate: blocked only when the field is password-typed AND the user has not opted into voice-everywhere. */
    fun isVoiceBlockedByPassword(): Boolean =
        isPasswordField() && !prefs.logicAllowVoiceInPassword.get()

    /**
     * Refuse a voice result in a password-typed field: on-keyboard notice
     * (a bare Toast is missable from an IME context) plus a best-effort
     * toast. Logs the inputType class/variation hex (type constants, never
     * content) so a false positive is exactly diagnosable from the log.
     */
    private fun refuseVoiceInPassword() {
        val inputType = try {
            (currentInputEditorInfo ?: if (::editorInfo.isInitialized) editorInfo else null)?.inputType
        } catch (_: Exception) {
            null
        }
        val typeHex = inputType?.let { "class=0x${(it and InputType.TYPE_MASK_CLASS).toString(16)} variation=0x${(it and InputType.TYPE_MASK_VARIATION).toString(16)}" } ?: "unknown-type"
        AppLog.d("IME", "drop voice result: password-field $typeHex")
        // The mic-pill label only shows errorMessageLD in error/unknown
        // states (ViewManager.MicPill label switch) — a bare post would be
        // invisible once the state falls back to READY, so also enter the
        // error state (next recognizer state overwrites it, same pattern
        // as the mic-permission and recognizer-error paths).
        viewManager.errorMessageLD.postValue(R.string.ime_voice_blocked_password)
        viewManager.stateLD.postValue(ViewManager.STATE_ERROR)
        try {
            Toast.makeText(this, R.string.ime_voice_blocked_password, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    /**
     * I-02/I-08 diagnostics: describes why the live session looks stale
     * (package/token/view/connection). Informational only — results are
     * committed regardless (pre-audit "works everywhere" behavior).
     * No PII: reason codes only, never text.
     */
    private fun dropReason(): String? {
        if (!inputViewShown) return "input-view-hidden"
        if (!::editorInfo.isInitialized) return "no-editor-info"
        if (sessionPackage != null && sessionPackage != editorInfo.packageName) return "package-changed"
        val live = token
        if (sessionToken != null && live != null && sessionToken != live) return "token-changed"
        if (currentInputConnection == null) return "no-input-connection"
        return null
    }

    private fun findEnterAction(): Int {
        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        if (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 && action in editorActions) {
            return action
        }

        return EditorInfo.IME_ACTION_UNSPECIFIED
    }

    /**
     * Called when the keyboard is closed
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        if (BuildConfig.DEBUG) Log.d("IME", "@onFinishInputView")

        // I-08: results after this point belong to a dead session.
        inputViewShown = false
        // text input has ended
        setKeepScreenOn(false)
        if (prefs.logicRememberLocalePerApp.get() && ::editorInfo.isInitialized) {
            val pkg = editorInfo.packageName
            val tag = modelManager.currentLocale?.toLanguageTag()
            if (!pkg.isNullOrEmpty() && !tag.isNullOrEmpty()) {
                val current = prefs.localePerApp.get()
                // Write only on change, capped: the map is re-read on every
                // keyboard open and must not grow or churn unboundedly.
                if (current[pkg] != tag) {
                    val updated = (current + (pkg to tag))
                    prefs.localePerApp.set(
                        if (updated.size > ConfigBackupIO.MAX_LOCALE_PER_APP) {
                            updated.entries.toList().takeLast(ConfigBackupIO.MAX_LOCALE_PER_APP)
                                .associate { it.key to it.value }
                        } else {
                            updated
                        }
                    )
                }
            }
        }
        modelManager.stop()
        if (prefs.logicAutoSwitchBack.get()) {
            // switch back
            actionManager.switchToLastIme(false)
        }
    }

    /**
     * Called the first time the keyboard is opened after a configuration change
     */
    override fun onCreateInputView(): View {
        if (BuildConfig.DEBUG) Log.d("IME", "@onCreateInputView")

        // on rotation the system re-attaches the input view to a new window;
        // detach from the old parent first or setInputView crashes
        (viewManager.parent as? ViewGroup)?.removeView(viewManager)

        lifecycleOwner.attachToDecorView(
            window?.window?.decorView
        )

        return viewManager
    }

    private val viewManagerListener = object : ViewManager.Listener {
        override fun micClick() {
            if (!hasMicPermission || modelManager.openSettingsOnMic) {
                // errors! open settings
                actionManager.openSettings()
            } else if (modelManager.isRunning) {
                if (!modelManager.isCurrentSourceStreaming) {
                    // Record-then-transcribe source (Whisper/Parakeet):
                    // second tap stops recording and transcribes.
                    modelManager.stop()
                    syncKeepScreenOn(false)
                } else if (modelManager.isPaused) {
                    modelManager.pause(false)
                    syncKeepScreenOn(true)
                } else {
                    modelManager.pause(true)
                    syncKeepScreenOn(false)
                }
            } else {
                modelManager.start()
                syncKeepScreenOn(true)
            }
        }

        /** WHEN_LISTENING keep-awake follows the recording state. */
        private fun syncKeepScreenOn(listening: Boolean) {
            if (prefs.logicKeepScreenAwake.get() == KeepScreenAwakeMode.WHEN_LISTENING) {
                setKeepScreenOn(listening)
            }
        }

        override fun micLongClick(): Boolean {
            val imeManager =
                applicationContext.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imeManager.showInputMethodPicker()
            return true
        }

        override fun backClicked() {
            actionManager.switchToLastIme(true)
        }

        override fun backspaceClicked() {
            actionManager.deleteLastChar()
        }

        private var initX = 0f
        private var initY = 0f
        private val threshold: Float
            get() = resources.displayMetrics.densityDpi / 6f
        private val charLen: Float
            get() = resources.displayMetrics.densityDpi / 32f
        private var swiping = false
        private var restart = false

        override fun backspaceTouchStart(offset: Offset) {
            restart = true
            swiping = false
        }

        override fun backspaceTouched(change: PointerInputChange, dragAmount: Float) {
            if (restart) {
                restart = false
                initX = change.position.x
                initY = change.position.y
            }

            var x = change.position.x - initX
            val y = change.position.y - initY

            if (BuildConfig.DEBUG) Log.d("IME", "swipe")

            if (x < -threshold) {
                swiping = true
            }
            if (swiping) {
                x = -x // x is negative
                val amount = ((x - threshold) / charLen).roundToInt()
                actionManager.selectCharsBack(amount)
            }
        }

        override fun backspaceTouchEnd() {
            if (swiping) actionManager.deleteSelection()
        }

        override fun returnClicked() {
            actionManager.sendEnter()
        }

        override fun modelClicked() {
            modelManager.switchToNextRecognizer(prefs.logicListenImmediately.get())
        }

        override fun settingsClicked() {
            actionManager.openSettings()
        }

        override fun buttonClicked(text: String) {
            textManager.onText(text, TextManager.Mode.INSERT)
        }

        override fun spaceClicked() {
            textManager.onText(" ", TextManager.Mode.INSERT)
        }

        override fun deviceChanged(device: AudioDeviceInfo) {
            modelManager.recordDevice = device
        }

        override fun keyClicked(keyCode: Int) {
            actionManager.sendKey(keyCode)
        }

        override fun selectAllClicked() {
            actionManager.selectAll()
        }

        override fun copyClicked() {
            actionManager.copy()
        }

        override fun pasteClicked() {
            actionManager.paste()
        }
    }

    /**
     * Called when the current selection is updated (which happens when we write text, too - we need to make sure there aren't any loops)
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        if (BuildConfig.DEBUG) Log.d("IME", "@onUpdateSelection")

        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        actionManager.updateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        textManager.onUpdateSelection(newSelStart, newSelEnd)
    }

    /**
     * Called when the keyboard service is torn down. This happens when the
     * user switches to a different keyboard; the process itself may survive,
     * in which case the model stays in RAM (see ModelManager.onDestroy).
     */
    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) Log.d("IME", "@onDestroy")

        AppLog.d("IME", "onDestroy")
        lifecycleOwner.onDestroy()
        modelManager.onDestroy()
    }

    /** I-17: narrowed — ActionManager needs the token, nothing else does. */
    internal val token: IBinder?
        get() {
            val window = myWindow ?: return null
            return window.attributes.token
        }
    private val myWindow: Window?
        get() {
            val dialog = window ?: return null
            return dialog.window
        }

    private fun setKeepScreenOn(keepScreenOn: Boolean) {
        val window = myWindow ?: return
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun checkMicrophonePermission() {
        hasMicPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) {
            viewManager.errorMessageLD.postValue(R.string.mic_error_no_permission)
            viewManager.stateLD.postValue(ViewManager.STATE_ERROR)
        }
    }

    override fun onResult(text: String) {
        if (text.isEmpty()) return
        // I-09: never voice-commit into password fields (notice, no commit).
        if (isVoiceBlockedByPassword()) {
            refuseVoiceInPassword()
            return
        }
        // Pre-audit behavior: commit in every app. The session tag above is
        // diagnostics only (see dropReason) — a stale-looking session must
        // not silently swallow dictation.
        dropReason()?.let { AppLog.d("IME", "commit voice result despite: $it") }
        if (BuildConfig.DEBUG) Log.d("VoskIME", "Result length: ${text.length}")
        textManager.onText(text, TextManager.Mode.STANDARD)
    }

    override fun onFinalResult(text: String) {
        if (text.isEmpty()) return
        if (isVoiceBlockedByPassword()) {
            refuseVoiceInPassword()
            return
        }
        dropReason()?.let { AppLog.d("IME", "commit voice result despite: $it") }
        if (BuildConfig.DEBUG) Log.d("VoskIME", "Final result length: ${text.length}")
        textManager.onText(text, TextManager.Mode.FINAL)
    }

    override fun onPartialResult(partialText: String) {
        if (partialText == "") return
        if (isVoiceBlockedByPassword()) {
            refuseVoiceInPassword()
            return
        }
        dropReason()?.let { AppLog.d("IME", "commit voice result despite: $it") }
        if (BuildConfig.DEBUG) Log.d("VoskIME", "Partial result length: ${partialText.length}")

        textManager.onText(partialText, TextManager.Mode.PARTIAL)
    }

    override fun onStateChanged(state: ModelManager.State) {
        if (state == ModelManager.State.STATE_STOPPED) {
            currentRecognizerSource?.stateLD?.removeObserver(viewManager)
            // Back to Ready so the next tap starts a new take (the stopped
            // source reloads on demand if it was freed from RAM).
            viewManager.stateLD.postValue(ViewManager.STATE_READY)
        } else {

            viewManager.stateLD.postValue(
                when (state) {
                    ModelManager.State.STATE_INITIAL -> ViewManager.STATE_INITIAL
                    ModelManager.State.STATE_LOADING -> ViewManager.STATE_LOADING
                    ModelManager.State.STATE_READY -> ViewManager.STATE_READY
                    ModelManager.State.STATE_LISTENING ->
                        if (modelManager.isCurrentSourceStreaming) ViewManager.STATE_LISTENING
                        else ViewManager.STATE_LISTENING_OFFLINE
                    ModelManager.State.STATE_TRANSCRIBING -> ViewManager.STATE_TRANSCRIBING
                    ModelManager.State.STATE_PAUSED -> ViewManager.STATE_PAUSED
                    ModelManager.State.STATE_ERROR -> ViewManager.STATE_ERROR
                    // Future states surface as error, never crash (TODO()
                    // here would kill the keyboard on the next enum addition).
                    else -> ViewManager.STATE_ERROR
                }
            )
        }
    }

    override fun onError(type: ModelManager.ErrorType) {
        viewManager.errorMessageLD.postValue(
            when (type) {
                ModelManager.ErrorType.MIC_IN_USE -> R.string.mic_error_mic_in_use
                ModelManager.ErrorType.NO_RECOGNIZERS_INSTALLED -> R.string.mic_error_no_recognizers
            }
        )
    }

    override fun onError(e: Exception) {
        viewManager.errorMessageLD.postValue(R.string.mic_error_recognizer_error)
        viewManager.stateLD.postValue(ViewManager.STATE_ERROR)
    }

    override fun onRecognizerSource(source: RecognizerSource) {
        currentRecognizerSource?.stateLD?.removeObserver(viewManager)
        currentRecognizerSource = source
        source.stateLD.observe(lifecycleOwner, viewManager)
        viewManager.recognizerNameLD.postValue(currentRecognizerSource!!.name)
    }

    override fun onTimeout() {
        viewManager.stateLD.postValue(ViewManager.STATE_PAUSED)
    }

    companion object {
        /** I-22 strict shape for locale tags (locale/IME-id/keys pattern). */
        private val LOCALE_TAG_PATTERN = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
        private val editorActions = intArrayOf(
            EditorInfo.IME_ACTION_UNSPECIFIED,
            EditorInfo.IME_ACTION_NONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS
        )
    }
}