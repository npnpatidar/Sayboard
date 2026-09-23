package com.elishaazaria.sayboard.ime

import com.elishaazaria.sayboard.BuildConfig
import com.elishaazaria.sayboard.recognition.ModelManager
import com.elishaazaria.sayboard.sayboardPreferenceModel

class TextManager(private val ime: IME, private val modelManager: ModelManager) {
    private val prefs by sayboardPreferenceModel()

    private var addSpace = false
    private var capitalize = true
    private var firstSinceResume = true

    private var composing = false

    fun onUpdateSelection(
        newSelStart: Int,
        newSelEnd: Int,
    ) {
        if (!composing) {
            if (newSelStart == newSelEnd) { // cursor moved
                checkAddSpaceAndCapitalize()
            }
        }
    }

    fun onText(text: String, mode: Mode) {
        if (text.isEmpty())  // no need to commit empty text
            return
        // I-01: counts only, DEBUG-gated — never transcripts.
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "onText length: ${text.length}, mode: $mode")
        }

        if (firstSinceResume) {
            firstSinceResume = false
            checkAddSpaceAndCapitalize()
        }

        // I-09: password fields get plain manual inserts only — no voice
        // commit, no spacing/capitalization, no composing, no formatting,
        // unless the user opted into voice-everywhere.
        // (Voice STANDARD/FINAL/PARTIAL is already blocked in IME with a
        // notice; this is defense in depth.)
        if (ime.isVoiceBlockedByPassword() && mode != Mode.INSERT) {
            com.elishaazaria.sayboard.utils.AppLog.d(TAG, "drop commit: password-field mode=$mode")
            return
        }

        val ic = ime.currentInputConnection
        if (ic == null) {
            com.elishaazaria.sayboard.utils.AppLog.d(TAG, "drop commit: no-input-connection mode=$mode")
            return
        }

        var spacedText = text
        if (!ime.isPasswordField() && prefs.logicAutoCapitalize.get() && capitalize) {
            spacedText = spacedText[0].uppercase() + spacedText.substring(1)
        }

        if (!ime.isPasswordField() && modelManager.currentRecognizerSourceAddSpaces && addSpace) {
            spacedText = " $spacedText"
        }
        when (mode) {
            Mode.FINAL, Mode.STANDARD -> {
                // add a space next time. Usually overridden by onUpdateSelection
                addSpace = addSpaceAfter(
                    spacedText[spacedText.length - 1] // last char
                )
                if (!ime.isPasswordField()) {
                    capitalizeAfter(
                        spacedText
                    )?.let {
                        capitalize = it
                    }
                }
                composing = false
                commitTextOrKeys(ic, spacedText)
            }

            Mode.PARTIAL -> {
                // Terminal-style (TYPE_NULL) editors ignore composing text;
                // sending partial keystrokes would insert text prematurely,
                // so partials are shown only in rich editors.
                if (!ime.isRichTextEditorNow()) {
                    com.elishaazaria.sayboard.utils.AppLog.d(TAG, "skip partial: plain editor")
                    return
                }
                composing = true
                ic.setComposingText(spacedText, 1)
            }

            Mode.INSERT -> {                // Manual insert. Don't add a space.
                composing = false
                commitTextOrKeys(ic, text)
            }
        }
    }

    /**
     * Terminal-style (TYPE_NULL class) editors — Termux-like apps — ignore
     * commitText, like sendEnter already assumes (see ActionManager).
     * Route through raw key events via the virtual keymap. When a char has
     * no key mapping (non-Latin scripts, emoji), key events are impossible:
     * post a visible notice and still attempt commitText (harmless where
     * ignored) instead of failing silently.
     */
    private fun commitTextOrKeys(ic: android.view.inputmethod.InputConnection, text: String) {
        if (ime.isRichTextEditorNow()) {
            ic.commitText(text, 1)
            return
        }
        if (sendAsKeyEvents(ic, text)) {
            com.elishaazaria.sayboard.utils.AppLog.d(TAG, "commit via key-events chars=${text.length}")
            return
        }
        com.elishaazaria.sayboard.utils.AppLog.d(TAG, "no key mapping, notice + commit attempt chars=${text.length}")
        ime.showEditorNotice(com.elishaazaria.sayboard.R.string.ime_no_key_mapping)
        ic.commitText(text, 1)
    }

    private fun sendAsKeyEvents(ic: android.view.inputmethod.InputConnection, text: String): Boolean {
        val events = try {
            android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents(text.toCharArray())
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            for (e in events) ic.sendKeyEvent(e)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun checkAddSpaceAndCapitalize() {
        // I-09: never read surrounding text in password fields. F11: this
        // deliberately uses strict isPasswordField() (not the
        // voice-everywhere opt-in) — an opt-in user gets voice commits but
        // no auto-spacing/capitalization here, privacy-leaning by design.
        if (ime.isPasswordField()) {
            addSpace = false
            return
        }
        if (!modelManager.currentRecognizerSourceAddSpaces) {
            addSpace = false
            return
        }
        // I-02: null-safe IC use, small bounded read.
        val cs = try {
            ime.currentInputConnection?.getTextBeforeCursor(3, 0)
        } catch (_: Exception) {
            null
        }
        if (cs != null) {
            addSpace = cs.isNotEmpty() && addSpaceAfter(cs[cs.length - 1])

            val value = capitalizeAfter(cs)
            value?.let {
                capitalize = it
            }
        }
    }

    private fun capitalizeAfter(string: CharSequence): Boolean? {
        for (char in string.reversed()) {
            if (char.isLetterOrDigit()) {
                return false
            }
            if (char in sentenceTerminator) {
                return true
            }
        }
        return null
    }

    private fun addSpaceAfter(char: Char): Boolean = when (char) {
        '"' -> false
        '*' -> false
        ' ' -> false
        '\n' -> false
        '\t' -> false
        else -> true
    }

    fun onResume() {
        firstSinceResume = true;
    }

    enum class Mode {
        STANDARD, PARTIAL, FINAL, INSERT
    }

    companion object {
        private const val TAG = "TextManager"
        private val sentenceTerminator = charArrayOf('.', '\n', '!', '?')
    }
}
