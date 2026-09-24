package com.elishaazaria.sayboard.ime

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.elishaazaria.sayboard.BuildConfig
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.SettingsActivity
import com.elishaazaria.sayboard.sayboardPreferenceModel

class ActionManager(private val ime: IME, private val viewManager: ViewManager) {
    private val prefs by sayboardPreferenceModel()
    private val mInputMethodManager: InputMethodManager =
        ime.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val clipboardManager: ClipboardManager =
        ime.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var selectionStart = 0
    private var selectionEnd = 0

    fun onStartInputView() {
        // I-04: small-n lazy reads only, 64k cap, never retain full text.
        try {
            val ic = ime.currentInputConnection ?: return
            val req = ExtractedTextRequest().apply { hintMaxChars = EXTRACT_CAP }
            val et = ic.getExtractedText(req, 0)
            if (et != null) {
                selectionStart = et.selectionStart
                selectionEnd = et.selectionEnd
            } else {
                selectionStart = 0
                selectionEnd = 0
            }
        } catch (_: Exception) {
            selectionStart = 0
            selectionEnd = 0
        }
    }

    fun updateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        selectionStart = newSelStart
        selectionEnd = newSelEnd
    }

    fun selectCharsBack(chars: Int) {
        val ic = ime.currentInputConnection ?: return
        var start = selectionEnd - chars
        if (start < 0) start = 0
        ic.setSelection(start, selectionEnd)
    }

    fun selectAll() {
        val ic = ime.currentInputConnection ?: return
        if (!ic.performContextMenuAction(android.R.id.selectAll)) {
            // Fallback for editors ignoring the context-menu action.
            // I-04: capped read, no retained text beyond the selection call.
            try {
                val req = ExtractedTextRequest().apply { hintMaxChars = EXTRACT_CAP }
                val length = ic.getExtractedText(req, 0)?.text?.length ?: return
                ic.setSelection(0, length)
            } catch (_: Exception) {
                return
            }
        }
    }

    /**
     * I-03: copy requires a non-empty selection (toast otherwise); password
     * fields are skipped entirely. No copy-all fallback: it moves the cursor
     * and leaks full-text reads.
     */
    fun copy() {
        if (ime.isPasswordField()) return
        val ic = ime.currentInputConnection ?: return
        val selected = try {
            ic.getSelectedText(0)?.toString()
        } catch (_: Exception) {
            null
        }
        if (!selected.isNullOrEmpty()) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("sayboard", selected))
        } else {
            Toast.makeText(ime, R.string.ime_nothing_to_copy, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * I-12: paste capped at 8k; control chars are stripped except \n\t.
     * Oversize pastes ask first via the IME window (AlertDialog with
     * TYPE_APPLICATION_ATTACHED_DIALOG + the IME window token); any dialog
     * failure falls back to the truncate toast. The cap still applies after
     * confirm. Dialog uses dedicated `ime_paste_confirm_*` keys, buttons
     * `button_confirm`/`button_cancel`.
     */
    fun paste() {
        if (ime.isPasswordField()) return
        if (ime.currentInputConnection == null) return
        val clip = try {
            clipboardManager.primaryClip
        } catch (_: Exception) {
            return
        } ?: return
        if (clip.itemCount == 0) return
        val raw = try {
            clip.getItemAt(0).coerceToText(ime)?.toString()
        } catch (_: Exception) {
            null
        } ?: return
        if (raw.isEmpty()) return
        // Strip controls except \n\t (I-12/I-22).
        val stripped = raw.filter { c -> c == '\n' || c == '\t' || c.code >= 0x20 && c != '\u007F' }
        if (stripped.isEmpty()) return
        if (stripped.length <= PASTE_CAP) {
            commitPaste(stripped)
            return
        }
        val truncated = stripped.take(PASTE_CAP)
        try {
            showLargePasteDialog(truncated)
        } catch (_: Exception) {
            // No dialog infra in this IME state: legacy toast behavior.
            try {
                Toast.makeText(ime, R.string.ime_paste_truncated, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
            commitPaste(truncated)
        }
    }

    /** I-12: confirm an oversize (already capped) paste via the IME window. */
    private fun showLargePasteDialog(truncated: String) {
        val dialog = AlertDialog.Builder(ime)
            .setTitle(R.string.ime_paste_confirm_title)
            .setMessage(R.string.ime_paste_confirm_message)
            .setPositiveButton(R.string.button_confirm) { d, _ ->
                commitPaste(truncated)
                try {
                    d.dismiss()
                } catch (_: Exception) {
                }
            }
            .setNegativeButton(R.string.button_cancel) { d, _ ->
                try {
                    d.dismiss()
                } catch (_: Exception) {
                }
            }
            .create()
        try {
            dialog.window?.let { w ->
                val token = try {
                    ime.token
                } catch (_: Exception) {
                    null
                }
                if (token != null) {
                    w.attributes.token = token
                }
                w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
            }
        } catch (_: Exception) {
            // Token/type attach is best-effort; show() still attempted so the
            // outer catch can fall back to the toast path.
        }
        dialog.show()
    }

    private fun commitPaste(text: String) {
        val ic = ime.currentInputConnection ?: return
        try {
            ic.commitText(text, 1)
        } catch (_: Exception) {
        }
    }

    fun deleteSelection() {
        val ic = ime.currentInputConnection ?: return
        ic.commitText("", 1)
    }

    fun deleteLastChar() {
        // delete last char
        // Terminal-style (TYPE_NULL) editors ignore deleteSurroundingText;
        // deliver a raw DEL key event like sendEnter's non-rich fallback.
        if (!ime.isRichTextEditorNow()) {
            ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        val ic = ime.currentInputConnection ?: return
        val selectedChars = try {
            ic.getSelectedText(0)
        } catch (_: Exception) {
            null
        }
        if (selectedChars == null) {
            ic.deleteSurroundingText(1, 0)
        } else if (selectedChars.toString().isEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.performContextMenuAction(android.R.id.cut)
        }
    }

    fun sendEnter() {
        val ic = ime.currentInputConnection ?: return
        if (ime.enterAction == EditorInfo.IME_ACTION_UNSPECIFIED) {
            if (ime.isRichTextEditorNow()) {
                ic.commitText("\n", 1)
            } else {
                ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        } else {
            ic.performEditorAction(ime.enterAction)
        }
    }

    fun sendKey(keyCode: Int) {
        ime.sendDownUpKeyEvents(keyCode)
    }

    fun switchToLastIme(showError: Boolean) {
        // I-11: validate the stored default IME against the enabled list at
        // switch time; unknown ids are skipped with a toast, never passed on.
        val defaultIme = prefs.logicDefaultIME.get()
        val defaultKnown = defaultIme.isNotBlank() && isEnabledIme(defaultIme)
        if (prefs.logicReturnToDefaultIME.get() && defaultKnown) {
            ime.switchInputMethod(defaultIme)
            return
        }
        if (prefs.logicReturnToDefaultIME.get() && defaultIme.isNotBlank() && !defaultKnown) {
            if (BuildConfig.DEBUG) android.util.Log.d(TAG, "unknown default IME skipped")
            Toast.makeText(ime, R.string.toast_error_no_previous_ime, Toast.LENGTH_SHORT).show()
            return
        }
        val result: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ime.switchToPreviousInputMethod()
        } else {
            mInputMethodManager.switchToLastInputMethod(ime.token)
        }
        if (!result) {
            if (defaultKnown) {
                ime.switchInputMethod(defaultIme)
            } else {
                // switchToPreviousInputMethod is flaky by platform design:
                // false after reboot (no previous recorded), mid-switch
                // races, and some OEMs. Fall back to the system picker,
                // which always works; toast only if even that throws.
                try {
                    mInputMethodManager.showInputMethodPicker()
                } catch (_: Exception) {
                    if (defaultIme.isNotBlank() && !defaultKnown) {
                        Toast.makeText(ime, R.string.toast_error_no_previous_ime, Toast.LENGTH_SHORT)
                            .show()
                    } else if (showError) {
                        Toast.makeText(ime, R.string.toast_error_no_previous_ime, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }

    /** I-11/I-22: strict IME-id check against the enabled list. */
    private fun isEnabledIme(id: String): Boolean {
        if (!IME_ID_PATTERN.matches(id)) return false
        return try {
            mInputMethodManager.enabledInputMethodList.any { it.id == id }
        } catch (_: Exception) {
            false
        }
    }

    fun openSettings() {
        val myIntent = Intent(ime, SettingsActivity::class.java)
        myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ime.startActivity(myIntent)
    }

    companion object {
        private const val TAG = "ActionManager"
        /** I-04: cap on extracted-text reads. */
        private const val EXTRACT_CAP = 64 * 1024
        /** I-12: paste cap with truncate notice. */
        private const val PASTE_CAP = 8000
        private val IME_ID_PATTERN = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.]+$")
    }
}
