package com.elishaazaria.sayboard.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.elishaazaria.sayboard.BuildConfig
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.recognition.ModelManager
import com.elishaazaria.sayboard.recognition.SharedModelManager
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.sayboardPreferenceModel
import com.elishaazaria.sayboard.utils.AppLog
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exported system speech recognizer (other apps' voice input).
 *
 * B4/I-05 kill-switch: [logicAllowExternalRecognition] defaults OFF — when
 * off, sessions fail with ERROR_INSUFFICIENT_PERMISSIONS without touching
 * the mic or models. When on: the calling package is validated
 * (EXTRA_CALLING_PACKAGE with attribution fallback, I-10), sessions are
 * rate-limited (10s), an indicator notification is shown while serving,
 * and nothing auto-starts on bind.
 */
class SayboardRecognitionService : RecognitionService(), ModelManager.Listener {

    private val prefs by sayboardPreferenceModel()

    // I-07: no eager construction; the shared process-scoped manager is
    // attached lazily per session (the IME re-attaches itself on next open).
    private var modelManager: ModelManager? = null

    private var listener: Callback? = null

    private var lastPartialResult: String? = null

    /** I-14: exactly one terminal event per session. */
    private val terminalSent = AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var maxDurationFired = false
    private val maxDurationRunnable = Runnable {
        maxDurationFired = true
        terminalError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        try {
            modelManager?.stop(forceFreeRam = true, replayPendingStart = false)
        } catch (_: Exception) {
        }
        cancelIndicator()
    }

    /**************** RecognitionService functions ***************/

    // Never auto-start on bind: RecognitionService.onBind is left alone and
    // no models load until a validated onStartListening arrives.

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "onStartListening")
        terminalSent.set(false)
        maxDurationFired = false
        lastPartialResult = null
        listener ?: return
        recognizerIntent ?: run {
            terminalErrorOn(listener, SpeechRecognizer.ERROR_CLIENT)
            return
        }

        // B4/I-05 kill-switch: off = deny before mic/models are touched.
        val allowed = runCatching { prefs.logicAllowExternalRecognition.get() }.getOrDefault(false)
        if (!allowed) {
            terminalErrorOn(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        // I-10: single calling-package lookup (no package list query).
        val callingPkg = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE)
            ?: attributionPackage(listener)
        if (callingPkg.isNullOrBlank()) {
            AppLog.d(TAG, "external session denied: no calling package")
            terminalErrorOn(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        // I-05: rate-limit external sessions (min 10s between starts).
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastExternalSessionMs < EXTERNAL_SESSION_MIN_MS) {
            terminalErrorOn(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        lastExternalSessionMs = now

        this.listener = listener

        // I-15: validate/normalize EXTRA_LANGUAGE once; und/empty/bad ->
        // default with NO model reload. Unknown extras are ignored.
        val rawLang = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        val (locale, wantReload) = normalizeExternalLocale(rawLang)

        val attributionContext: Context? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                this.createContext(
                    ContextParams.Builder().setNextAttributionSource(listener.callingAttributionSource)
                        .build()
                )
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val manager = SharedModelManager.get(this, this)
        modelManager = manager

        // I-15: clamp external-triggered reloads (min 5s).
        if (wantReload && now - lastExternalReloadMs >= EXTERNAL_RELOAD_MIN_MS) {
            lastExternalReloadMs = now
            manager.reloadModels()
        }

        showIndicator()
        // I-06: service-side max-duration watchdog (120s). The silence
        // watchdog rides on the existing recognizer timeouts.
        mainHandler.removeCallbacks(maxDurationRunnable)
        mainHandler.postDelayed(maxDurationRunnable, MAX_SESSION_MS)

        if (!manager.switchToRecognizerOfLocale(locale, true, attributionContext)) {
            AppLog.d(TAG, "external session: no model match, default")
            manager.initializeFirstLocale(true, attributionContext)
        }
    }

    override fun onCancel(listener: Callback?) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "onCancel")
        terminalSent.set(true)
        mainHandler.removeCallbacks(maxDurationRunnable)
        cancelIndicator()
        this.listener = null
        try {
            modelManager?.stop(forceFreeRam = true, replayPendingStart = false)
        } catch (_: Exception) {
        }
    }

    override fun onStopListening(listener: Callback?) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "onStopListening")
        this.listener = listener
        try {
            modelManager?.stop(forceFreeRam = true, replayPendingStart = false)
        } catch (_: Exception) {
        }

        val final = lastPartialResult
        if (!final.isNullOrEmpty()) {
            terminalResults(final)
        } else {
            // I-14: empty take terminates as NO_MATCH, never an empty result.
            terminalError(SpeechRecognizer.ERROR_NO_MATCH)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(maxDurationRunnable)
        cancelIndicator()
        listener = null
        super.onDestroy()
    }

    /************* ModelManager.Listener functions ***********/

    override fun onStateChanged(state: ModelManager.State) {
        if (state == ModelManager.State.STATE_LISTENING) {
            try {
                listener?.readyForSpeech(Bundle())
                listener?.beginningOfSpeech()
            } catch (e: RemoteException) {
                // I-06: dead caller — stop and drop the listener.
                if (BuildConfig.DEBUG) android.util.Log.e(TAG, "caller dead", e)
                listener = null
                try {
                    modelManager?.stop(forceFreeRam = true, replayPendingStart = false)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onError(type: ModelManager.ErrorType) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "onError")
        terminalError(
            when (type) {
                // I-07: busy mic maps to RECOGNIZER_BUSY.
                ModelManager.ErrorType.MIC_IN_USE -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                ModelManager.ErrorType.NO_RECOGNIZERS_INSTALLED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                } else {
                    SpeechRecognizer.ERROR_CLIENT
                }
            }
        )
    }

    override fun onError(exception: Exception?) {
        if (BuildConfig.DEBUG) android.util.Log.e(TAG, "onError", exception)
        terminalError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
    }

    override fun onRecognizerSource(source: RecognizerSource) {
    }

    override fun onPartialResult(hypothesis: String?) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "onPartialResult")
        }
        lastPartialResult = hypothesis
        try {
            listener?.partialResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(hypothesis))
            })
        } catch (e: RemoteException) {
            // I-06: dead caller — stop and drop the listener.
            if (BuildConfig.DEBUG) android.util.Log.e(TAG, "caller dead", e)
            listener = null
            try {
                modelManager?.stop(forceFreeRam = true, replayPendingStart = false)
            } catch (_: Exception) {
            }
        }
    }

    override fun onResult(hypothesis: String?) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "onResult")
        }
        // Konele seems to assume that results -> end of speech, so call onFinalResult to clean up too.
        onFinalResult(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "onFinalResult")
        }
        lastPartialResult = null
        if (hypothesis.isNullOrEmpty()) {
            // I-14: empty take terminates as NO_MATCH, never an empty result.
            terminalError(SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        terminalResults(hypothesis)
        try {
            modelManager?.stop(forceFreeRam = true, replayPendingStart = false)
        } catch (_: Exception) {
        }
    }

    override fun onTimeout() {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "onTimeout")
        // I-14: silence timeout terminates as TIMEOUT.
        terminalError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
    }

    /** I-14: single terminal results event (first call wins). */
    private fun terminalResults(hypothesis: String) {
        if (!terminalSent.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(maxDurationRunnable)
        cancelIndicator()
        try {
            listener?.results(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(hypothesis))
            })
            listener?.endOfSpeech()
        } catch (e: RemoteException) {
            if (BuildConfig.DEBUG) android.util.Log.e(TAG, "caller dead", e)
        } finally {
            listener = null
        }
    }

    /** I-14: single terminal error event (first call wins). */
    private fun terminalError(code: Int) {
        if (!terminalSent.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(maxDurationRunnable)
        cancelIndicator()
        try {
            listener?.error(code)
        } catch (e: RemoteException) {
            if (BuildConfig.DEBUG) android.util.Log.e(TAG, "caller dead", e)
        } finally {
            listener = null
        }
    }

    /** Terminal error to a not-yet-attached callback (pre-session deny). */
    private fun terminalErrorOn(target: Callback, code: Int) {
        terminalSent.set(true)
        try {
            target.error(code)
        } catch (e: RemoteException) {
            if (BuildConfig.DEBUG) android.util.Log.e(TAG, "caller dead", e)
        }
    }

    /** I-10 attribution fallback for the calling package. */
    private fun attributionPackage(listener: Callback): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listener.callingAttributionSource?.packageName
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * I-15/I-22: normalize EXTRA_LANGUAGE once. Null/empty/`und`/misshapen
     * tags fall back to the default WITHOUT a model reload; only a
     * well-formed non-default tag triggers one (still rate-clamped).
     */
    private fun normalizeExternalLocale(raw: String?): Pair<Locale, Boolean> {
        val def = Locale.getDefault()
        if (raw.isNullOrBlank()) return def to false
        val tag = raw.trim().replace('_', '-')
        if (tag.equals("und", ignoreCase = true)) return def to false
        if (!EXTERNAL_LOCALE_PATTERN.matches(tag)) return def to false
        return try {
            val locale = Locale.forLanguageTag(tag)
            if (locale.language.isEmpty() || locale.language == "und") def to false
            else locale to true
        } catch (_: Exception) {
            def to false
        }
    }

    /**
     * I-05: ongoing indicator while serving an external session
     * (best-effort: dropped silently when notifications are denied).
     */
    private fun showIndicator() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(EXTERNAL_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        EXTERNAL_CHANNEL_ID,
                        getString(R.string.notification_keep_alive_channel),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            val notification = NotificationCompat.Builder(this, EXTERNAL_CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_keep_alive_title))
                .setContentText(getString(R.string.mic_info_recording))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build()
            manager.notify(EXTERNAL_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun cancelIndicator() {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(EXTERNAL_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "SayboardRecognitionService"
        /** I-06: service-side max session duration. */
        private const val MAX_SESSION_MS = 120_000L
        /** I-05: min gap between external sessions. */
        private const val EXTERNAL_SESSION_MIN_MS = 10_000L
        /** I-15: min gap between external-triggered model reloads. */
        private const val EXTERNAL_RELOAD_MIN_MS = 5_000L
        private val EXTERNAL_LOCALE_PATTERN =
            Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$")
        private const val EXTERNAL_CHANNEL_ID = "sayboard_recognition"
        private const val EXTERNAL_NOTIFICATION_ID = 5

        @Volatile
        private var lastExternalSessionMs = 0L
        @Volatile
        private var lastExternalReloadMs = 0L
    }
}
