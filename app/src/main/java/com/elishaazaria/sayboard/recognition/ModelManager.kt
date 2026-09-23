package com.elishaazaria.sayboard.recognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.elishaazaria.sayboard.BuildConfig
import com.elishaazaria.sayboard.Constants
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.Tools
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.ime.ViewManager
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.providers.Providers
import com.elishaazaria.sayboard.recognition.recognizers.sources.SherpaWhisper
import com.elishaazaria.sayboard.sayboardPreferenceModel
import com.elishaazaria.sayboard.utils.AppLog
import org.vosk.android.RecognitionListener
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrator over the installed recognizer sources: model list,
 * locale routing, take lifecycle (start/stop/pause), and listener fan-out.
 *
 * Threading: [executor] is the single serial worker for loads, stops and
 * transcribe-decodes; [start]/[stop]/[pause] are main-thread entry points.
 *
 * Tunables (R16):
 * - Keep-in-RAM (`logicKeepModelInRam` pref): close(false) keeps the
 *   native model loaded across keyboard switches for instant rebind at
 *   the cost of hundreds of MB resident; close(true) frees it.
 * - VAD auto-stop (`logicVadAutoStop` pref, non-streaming only): trailing
 *   silence ends the take via [VadHolder] thresholds (`threshold`,
 *   `minSilence`, `minSpeech`); see [MySpeechService] caps
 *   (120s non-streaming / 300s streaming, 15s pre-speech, 10MB buffered).
 * - Engine threads/cpu/debug are set per source (Whisper/Parakeet 1..4
 *   threads by CPU, streaming single-thread, all `cpu` + `debug=false`).
 * - Audio focus: transient gain per take, abandoned on stop; focus loss
 *   pauses (never auto-resumes).
 */
class ModelManager(
    context: Context,
    listener: Listener
) {
    private val appContext: Context = context.applicationContext
    private val prefs by sayboardPreferenceModel()
    /** R15: atomic listener swap; dispatch sites snapshot via [activeListener]. */
    private val listenerRef = AtomicReference<Listener>(listener)
    private fun activeListener(): Listener = listenerRef.get()
    private var speechService: MySpeechService? = null
    var isRunning = false
        private set

    val openSettingsOnMic: Boolean
        get() = recognizerSources.size == 0

    private var recognizerSourceProviders = Providers(appContext)

    /**
     * R11: volatile resolve cache — the fast path in [reloadModels] returns
     * the live list immediately when the prefs order still matches this.
     */
    @Volatile
    /** Refs aligned with [sourceRefs]-tracked sources; see reloadModels. */
    private var recognizerSourceModels: List<InstalledModelReference> = listOf()
    /** Refs that actually resolved, aligned 1:1 with [recognizerSources]. */
    private var sourceRefs: List<InstalledModelReference> = listOf()

    /**
     * R11 single-flight: only one resolve owns the executor scan at a time;
     * concurrent callers keep serving the last-known list.
     */
    private val reloadFlight = AtomicBoolean(false)
    private var recognizerSources: MutableList<RecognizerSource> = ArrayList()
    private var currentRecognizerSourceIndex = 0
    private var currentRecognizerSource: RecognizerSource? = null
    /** Indices already tried by load-fallback in this failure chain.
     * F9: main-thread confined — mutated only on the start/initialize path
     * (main entry points); a future refactor moving those off-main must
     * synchronize this set and [loadFailedUntil]. */
    private val loadFallbackTried = mutableSetOf<Int>()
    /** Index -> uptime-ms until which auto-switch must skip it (load failed). */
    private val loadFailedUntil = mutableMapOf<Int, Long>()

    /** True while the index is blacklisted after a load failure. */
    private fun isBlacklisted(index: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        loadFailedUntil.entries.removeAll { it.value <= now }
        return (loadFailedUntil[index] ?: 0L) > now
    }
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * True while a stop (including a record-then-transcribe decode) is in
     * flight on [executor]. [start] waits it out; tasks stay serialized.
     */
    private val stopping = AtomicBoolean(false)
    /**
     * Mic tap that arrived while [stopping]: replayed on the main thread
     * once the stop completes instead of being silently dropped.
     */
    @Volatile
    private var hasPendingStart = false
    /** R8: pending tap context held weakly; never leaks an Activity. */
    @Volatile
    private var pendingStartRef: WeakReference<Context>? = null

    // I-20 audio focus: transient gain per take, pause on loss, no resume.
    private val audioManager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            // Pause (releases the mic); never auto-resume on gain.
            try {
                mainHandler.post { pause(true) }
            } catch (_: Throwable) {
            }
        }
    }


    init {
        reloadModels()
        warmResolveCacheAsync()
    }

    /**
     * R11 startup warm of the resolve path: runs the provider disk scan on
     * the model-load executor (never the caller thread, never the audio
     * thread) so later resolves hit warm page cache. The result is
     * intentionally dropped — the authoritative order still comes from
     * prefs in [reloadModels].
     */
    private fun warmResolveCacheAsync() {
        try {
            executor.execute {
                try {
                    recognizerSourceProviders.installedModels()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun initializeRecognizer(
        autoStart: Boolean,
        attributionContext: Context? = null,
        reloadStart: Boolean = false
    ) {
        if (recognizerSources.size == 0) {
            return
        }
        currentRecognizerSource = recognizerSources[currentRecognizerSourceIndex]
        val startedSource = currentRecognizerSource ?: return
        activeListener().onRecognizerSource(startedSource)

        val onLoaded = Observer { r: RecognizerSource? ->
            if (autoStart) {
                start(attributionContext, reloadStart) // execute after initialize
            }
        }
        startedSource.initialize(executor, onLoaded)
        // R11: warm universal-check + VAD off the caller thread on the
        // model-load executor (never main, never the audio thread).
        try {
            executor.execute {
                try {
                    startedSource.isUniversal
                } catch (_: Throwable) {
                }
                if (startedSource is SherpaWhisper) {
                    try {
                        startedSource.warmUniversalAsync(executor)
                    } catch (_: Throwable) {
                    }
                }
                try {
                    VadHolder.prewarm(appContext)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    val currentRecognizerSourceAddSpaces: Boolean
        get() = currentRecognizerSource?.addSpaces ?: true

    /** False for record-then-transcribe sources (Whisper/Parakeet). */
    val isCurrentSourceStreaming: Boolean
        get() = currentRecognizerSource?.isStreaming ?: true

    /** Locale of the active recognizer, for per-app memory and toasts. */
    val currentLocale: Locale?
        get() = currentRecognizerSource?.locale

    /** Display name of the active recognizer, for switch toasts. */
    val currentSourceName: String?
        get() = currentRecognizerSource?.name

    /**
     * Per-model RAM pin: pinned models stay resident once loaded (switches
     * and rebinds reuse them with zero reload). Absent from the unpinned
     * map = pinned (default: everything stays loaded). Each individual
     * load is still memory-gated (R3); unpin per model on low-RAM devices.
     * F4: keys are relative going forward; legacy absolute keys still match
     * via [Tools.isPathPinned] canonical comparison.
     */
    private fun isPinned(ref: InstalledModelReference): Boolean =
        runCatching {
            val dir = runCatching { Constants.getModelsDirectory(appContext) }.getOrNull()
            val abs = ref.resolveAbsolute(dir).absolutePath
            Tools.isPathPinned(prefs.unpinnedModels.get(), dir, abs)
        }.getOrDefault(true)

    /**
     * F3: sum-aware soft pin guard — call OFF the main thread (walks model
     * dirs + queries ActivityManager). Refuses pinning when the pinned
     * resident set plus the candidate would exceed half of current
     * availMem (same budget as the R3 per-load gate). Fail-open (true)
     * when sizing is unavailable so the toggle never bricks on I/O error.
     */
    fun canPinResident(candidate: InstalledModelReference): Boolean {
        return try {
            val dir = Constants.getModelsDirectory(appContext)
            val candAbs = candidate.resolveAbsolute(dir).absolutePath
            val pinnedAbs = sourceRefs
                .filter { it.path != candidate.path && isPinned(it) }
                .map { it.resolveAbsolute(dir).absolutePath }
            Tools.residentFitsRam(appContext, candAbs, pinnedAbs)
        } catch (_: Exception) {
            true
        }
    }

    /** Ref of the current source, for per-model pin decisions on switch. */
    private fun refOfCurrent(): InstalledModelReference? =
        sourceRefs.getOrNull(currentRecognizerSourceIndex)

    fun switchToNextRecognizer(autoStart: Boolean, attributionContext: Context? = null) {
        if (recognizerSources.size == 0 || recognizerSources.size == 1) return
        // Free the old model only when it is unpinned; pinned models stay
        // resident so switching back is instant.
        val freeOld = refOfCurrent()?.let { !isPinned(it) } ?: true
        stop(forceFreeRam = freeOld)
        currentRecognizerSourceIndex++
        if (currentRecognizerSourceIndex >= recognizerSources.size) {
            currentRecognizerSourceIndex = 0
        }
        initializeRecognizer(autoStart, attributionContext) // start is called after the recognizer is initialized
    }

    fun switchToRecognizerOfLocale(
        locale: Locale,
        autoStart: Boolean,
        attributionContext: Context? = null
    ): Boolean {
        val bestSource = bestSourceForLocale(locale)
        if (bestSource == -1) {
            return false
        }

        stop(forceFreeRam = refOfCurrent()?.let { !isPinned(it) } ?: true)
        currentRecognizerSourceIndex = bestSource

        initializeRecognizer(
            autoStart,
            attributionContext
        ) // start is called after the recognizer is initialized

        return true
    }

    /**
     * Switch to the best model for [locale] unless it is already active.
     * Used for automatic per-field switching: no stop/reload when nothing
     * would change. When nothing matches, a universal source (multilingual
     * Whisper) is preferred over keeping an unrelated model; otherwise the
     * current model stays. Returns false only when no usable model exists.
     */
    fun maybeSwitchToLocale(locale: Locale): Boolean {
        if (recognizerSources.size == 0) return false
        val bestSource = bestSourceForLocale(locale)
        if (bestSource != -1) {
            if (isBlacklisted(bestSource)) {
                AppLog.d(TAG, "auto-switch skipping recently-failed index=$bestSource")
            } else {
                if (bestSource == currentRecognizerSourceIndex && currentRecognizerSource != null) {
                    return true
                }
                AppLog.d(TAG, "auto-switch model index=$bestSource")
                stop(forceFreeRam = refOfCurrent()?.let { !isPinned(it) } ?: true)
                currentRecognizerSourceIndex = bestSource
                initializeRecognizer(false)
                return true
            }
        }
        val universal = recognizerSources.indices.firstOrNull {
            recognizerSources[it].isUniversal && !isBlacklisted(it)
        } ?: -1
        if (universal != -1 &&
            (universal != currentRecognizerSourceIndex || currentRecognizerSource == null)
        ) {
            AppLog.d(TAG, "no model match, falling back to universal index $universal")
            stop(forceFreeRam = refOfCurrent()?.let { !isPinned(it) } ?: true)
            currentRecognizerSourceIndex = universal
            initializeRecognizer(false)
        }
        return universal != -1
    }

    /** Best recognizer index for [locale], or -1 when nothing matches. */
    private fun bestSourceForLocale(locale: Locale): Int {
        var bestSource = -1
        var foundLanguage = false
        var foundCountry = false

        recognizerSources.forEachIndexed { index, recognizerSource ->
            if (recognizerSource.locale.language == locale.language) {
                if (recognizerSource.locale.country == locale.country) {
                    if (recognizerSource.locale.variant == locale.variant) {
                        // Same language, country, and variant
                        bestSource = index
                        foundLanguage = true
                        foundCountry = true
                        return@forEachIndexed
                    } else if (!foundCountry) {
                        // Same language and country, but not variant
                        bestSource = index
                        foundLanguage = true
                        foundCountry = true
                    }
                } else if (!foundLanguage) {
                    // Same language, but not country
                    foundLanguage = true
                    bestSource = index
                }
            } else if (recognizerSource.locale == Locale.ROOT && !foundLanguage && bestSource == -1) {
                // A root locale. Pick it if we didn't find anything.
                bestSource = index
            }
        }
        return bestSource
    }

    fun initializeFirstLocale(autoStart: Boolean, attributionContext: Context? = null): Boolean {
        if (recognizerSources.size == 0) {
            activeListener().onError(ErrorType.NO_RECOGNIZERS_INSTALLED)
            activeListener().onStateChanged(State.STATE_ERROR)
            return false
        }

        val current = currentRecognizerSource
        if (current != null && !current.closed && recognizerSources.contains(current)) {
            // Already in RAM (e.g. after switching apps): re-announce without
            // a native reload. Previously this always reloaded index 0.
            activeListener().onRecognizerSource(current)
            if (autoStart) {
                start(attributionContext)
            }
            return true
        }
        currentRecognizerSourceIndex = 0
        initializeRecognizer(autoStart, attributionContext)
        return true
    }

    fun start(attributionContext: Context? = null, reloadStart: Boolean = false) {
        val listener = activeListener()
        val source = currentRecognizerSource
        if (source == null) {
            Log.w(
                TAG,
                "currentRecognizerSource is null!"
            )
            AppLog.e(TAG, "start refused: no current source")
            return
        }
        if (stopping.get()) {
            // A stop/transcribe (e.g. a multi-second Whisper decode) is in
            // flight: queue the tap and replay it when the stop completes.
            AppLog.d(TAG, "start during stop, queueing pending start")
            hasPendingStart = true
            pendingStartRef = if (attributionContext != null) WeakReference(attributionContext) else null
            return
        }
        if (source.closed) {
            if (reloadStart) {
                // Reload was already attempted and the source still failed:
                // fall back to the next untried source (single bounded pass)
                // so one broken model can never wedge the mic. Only when
                // every source failed do we surface ERROR.
                val next = recognizerSources.indices.firstOrNull {
                    it != currentRecognizerSourceIndex && it !in loadFallbackTried
                }
                if (next != null) {
                    loadFallbackTried.add(currentRecognizerSourceIndex)
                    AppLog.e(
                        TAG,
                        "reload failed index=$currentRecognizerSourceIndex, " +
                            "falling back to $next"
                    )
                    currentRecognizerSourceIndex = next
                    initializeRecognizer(
                        autoStart = true,
                        attributionContext = attributionContext,
                        reloadStart = true
                    )
                    return
                }
                loadFallbackTried.clear()
                // Blacklist briefly so auto-switch stops re-picking a broken
                // model on every keyboard open (user stays on last working).
                loadFailedUntil[currentRecognizerSourceIndex] =
                    SystemClock.uptimeMillis() + LOAD_FAIL_BLACKLIST_MS
                Log.w(TAG, "recognizer source failed to load")
                AppLog.e(TAG, "reload failed")
                listener.onStateChanged(State.STATE_ERROR)
                return
            }
            // Source was freed from RAM (e.g. after a transcribe-stop):
            // reload it, then start once loaded.
            AppLog.d(TAG, "source closed, reloading before start")
            initializeRecognizer(
                autoStart = true,
                attributionContext = attributionContext,
                reloadStart = true
            )
            return
        }
        // Source is open: any prior fallback chain resolved successfully.
        loadFallbackTried.clear()
        // R9: permission FIRST, before any isRunning/LISTENING state.
        if (ActivityCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.e(TAG, "start refused: RECORD_AUDIO not granted")
            listener.onStateChanged(State.STATE_ERROR)
            return
        }
        // I-07 arbiter: a second concurrent start while LISTENING is
        // deterministically rejected with an error (the external service
        // maps MIC_IN_USE to ERROR_RECOGNIZER_BUSY itself). No new API.
        val existing = speechService
        if (isRunning || existing != null) {
            listener.onError(ErrorType.MIC_IN_USE)
            return
        }
        val recognizer: Recognizer = try {
            source.recognizer
        } catch (t: Throwable) {
            AppLog.e(TAG, "recognizer handle failed kind=${t.javaClass.simpleName}")
            if (reloadStart) {
                // Already retried once: do not hot-loop reloads. Close for
                // real (close(false) is a no-op by contract) so the
                // re-entered start() sees closed==true and runs the bounded
                // cross-source fallback instead of recursing forever.
                try {
                    source.close(true)
                } catch (_: Throwable) {
                }
                start(attributionContext, true)
                return
            }
            initializeRecognizer(
                autoStart = true,
                attributionContext = attributionContext,
                reloadStart = true
            )
            return
        }
        requestAudioFocus()
        val service: MySpeechService = try {
            MySpeechService(
                recognizer,
                recognizer.sampleRate,
                attributionContext,
                vadAutoStop = prefs.logicVadAutoStop.get() &&
                    source.isStreaming == false,
                vadContext = appContext,
                // VAD already decoded before the final result: stop quietly.
                onTakeFinished = { stop(announceTranscribing = false) }
            )
        } catch (e: SecurityException) {
            abandonAudioFocus()
            listener.onError(ErrorType.MIC_IN_USE)
            listener.onStateChanged(State.STATE_ERROR)
            return
        } catch (e: RuntimeException) {
            abandonAudioFocus()
            listener.onError(ErrorType.MIC_IN_USE)
            listener.onStateChanged(State.STATE_ERROR)
            return
        } catch (t: Throwable) {
            abandonAudioFocus()
            if (t is OutOfMemoryError) System.gc()
            listener.onError(ErrorType.MIC_IN_USE)
            listener.onStateChanged(State.STATE_ERROR)
            return
        }
        try {
            service.recordDevice = recordDevice
        } catch (_: Throwable) {
        }
        val started = try {
            service.startListening(listener)
        } catch (e: SecurityException) {
            abandonAudioFocus()
            try {
                service.shutdown()
            } catch (_: Throwable) {
            }
            listener.onError(ErrorType.MIC_IN_USE)
            listener.onStateChanged(State.STATE_ERROR)
            return
        } catch (e: RuntimeException) {
            abandonAudioFocus()
            try {
                service.shutdown()
            } catch (_: Throwable) {
            }
            listener.onError(ErrorType.MIC_IN_USE)
            listener.onStateChanged(State.STATE_ERROR)
            return
        } catch (t: Throwable) {
            abandonAudioFocus()
            if (t is OutOfMemoryError) System.gc()
            try {
                service.shutdown()
            } catch (_: Throwable) {
            }
            listener.onError(ErrorType.MIC_IN_USE)
            listener.onStateChanged(State.STATE_ERROR)
            return
        }
        if (!started) {
            // startListening refused (already running): deterministic busy.
            abandonAudioFocus()
            try {
                service.shutdown()
            } catch (_: Throwable) {
            }
            listener.onError(ErrorType.MIC_IN_USE)
            return
        }
        // R9: visible state only after a true start.
        speechService = service
        isRunning = true
        listener.onStateChanged(State.STATE_LISTENING)
    }

    private var pausedState = false

    /** I-20: transient gain for the take; loss pauses via [audioFocusListener]. */
    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (_: Throwable) {
        }
    }

    /** I-20: abandoned when the take stops (executor completion). */
    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = audioFocusRequest
                if (req != null) {
                    audioManager.abandonAudioFocusRequest(req)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(audioFocusListener)
                }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * R11: the resolve (provider stat/scan per model) no longer runs inline
     * on the caller thread. Fast path: the volatile-cached order is still
     * fresh, so the live list is returned immediately. On a cache miss the
     * scan runs on the existing [executor] while the caller blocks with a
     * bounded [RELOAD_TIMEOUT_MS] wait — never ANR forever; on timeout the
     * last-known list keeps serving and an ERROR note is logged. Public
     * signature unchanged.
     */
    fun reloadModels() {
        val newModels = prefs.modelsOrder.get()
        if (newModels == recognizerSourceModels)
            return
        // Single-flight: a concurrent reload already owns the scan; keep
        // serving the last-known list instead of stacking another one.
        if (!reloadFlight.compareAndSet(false, true)) {
            AppLog.d(TAG, "reloadModels single-flight, keeping last-known")
            return
        }
        try {
            val providers = recognizerSourceProviders
            val task = FutureTask<List<Pair<InstalledModelReference, RecognizerSource>>> {
                val out = ArrayList<Pair<InstalledModelReference, RecognizerSource>>(newModels.size)
                for (model in newModels) {
                    try {
                        providers.recognizerSourceForModel(model)?.let {
                            out.add(model to it)
                        }
                    } catch (t: Throwable) {
                        if (t is OutOfMemoryError) System.gc()
                        AppLog.e(TAG, "resolve failed type=${model.type}")
                    }
                }
                out
            }
            val submitted = try {
                executor.execute(task)
                true
            } catch (t: Throwable) {
                AppLog.e(TAG, "reloadModels executor rejected kind=${t.javaClass.simpleName}")
                task.cancel(false)
                false
            }
            val resolved: List<Pair<InstalledModelReference, RecognizerSource>>? = if (!submitted) {
                null
            } else try {
                task.get(RELOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                task.cancel(true)
                Log.e(TAG, "reloadModels timeout, keeping last-known")
                AppLog.e(TAG, "reloadModels timeout ms=$RELOAD_TIMEOUT_MS, keeping last-known")
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                AppLog.e(TAG, "reloadModels interrupted, keeping last-known")
                null
            } catch (t: Throwable) {
                AppLog.e(TAG, "reloadModels failed kind=${t.javaClass.simpleName}, keeping last-known")
                null
            }
            if (resolved == null) {
                // Timeout/failure: keep the last-known list; an empty list
                // is still an install error, posted as before.
                if (recognizerSources.isEmpty()) {
                    activeListener().onError(ErrorType.NO_RECOGNIZERS_INSTALLED)
                    activeListener().onStateChanged(State.STATE_ERROR)
                }
                return
            }

            // Multi-model RAM: reuse already-loaded sources whose model is
            // still installed AND pinned, instead of closing + rebuilding
            // them — switches and rebinds become instant. Unpinned models,
            // vanished models (or broken ones) are released as before.
            val prevPairs = sourceRefs.zip(recognizerSources)
            val consumed = HashSet<RecognizerSource>()
            val finalSources = ArrayList<RecognizerSource>(resolved.size)
            for ((ref, fresh) in resolved) {
                val match = if (isPinned(ref)) {
                    prevPairs.firstOrNull {
                        it.first.path == ref.path && it.first.type == ref.type &&
                            !it.second.closed && it.second !in consumed
                    }?.second
                } else {
                    null
                }
                if (match != null) {
                    finalSources.add(match)
                    consumed.add(match)
                    AppLog.d(TAG, "reloadModels reusing loaded leaf=${ref.path.substringAfterLast('/').take(40)}")
                } else {
                    finalSources.add(fresh)
                }
            }
            val prevCurrent = currentRecognizerSource
            val discarded = recognizerSources.filter { it !in consumed }
            recognizerSources.clear()
            recognizerSources.addAll(finalSources)
            recognizerSourceModels = newModels
            sourceRefs = resolved.map { it.first }
            val keptIndex = if (prevCurrent != null && prevCurrent in consumed) {
                finalSources.indexOf(prevCurrent)
            } else {
                -1
            }
            if (keptIndex >= 0) {
                // Current model survived the rebuild: keep serving it with
                // zero reload.
                currentRecognizerSource = prevCurrent
                currentRecognizerSourceIndex = keptIndex
                AppLog.d(TAG, "reloadModels kept current index=$keptIndex, no reload")
            } else {
                currentRecognizerSource = null
                currentRecognizerSourceIndex = 0
            }
            loadFallbackTried.clear()
            loadFailedUntil.clear()
            executor.execute {
                discarded.forEach {
                    try {
                        it.close(true)
                    } catch (_: Exception) {
                    } catch (t: Throwable) {
                        if (t is OutOfMemoryError) System.gc()
                    }
                }
            }
            // R4: counts always persist; leaf names only in debug builds.
            // Free-form dictated text was never classified into log tiers,
            // so leaf gating (not redaction) is the fix here — transcript
            // safety rests on the upstream I-01 DEBUG-gating of
            // text-bearing logs.
            if (BuildConfig.DEBUG) {
                AppLog.d(
                    TAG, "reloadModels order=${newModels.size} " +
                        "resolved=${recognizerSources.size} " +
                        "leaves=${newModels.map { it.path.substringAfterLast('/').take(40) }}"
                )
            } else {
                AppLog.d(
                    TAG, "reloadModels order=${newModels.size} " +
                        "resolved=${recognizerSources.size}"
                )
            }
        // R11: warm universal-checks + VAD off the caller thread.
        try {
            executor.execute {
                for (s in recognizerSources.toList()) {
                    try {
                        s.isUniversal
                    } catch (_: Throwable) {
                    }
                    try {
                        (s as? SherpaWhisper)?.warmUniversalAsync(executor)
                    } catch (_: Throwable) {
                    }
                }
                try {
                    VadHolder.prewarm(appContext)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        if (recognizerSources.size == 0) {
            activeListener().onError(ErrorType.NO_RECOGNIZERS_INSTALLED)
            activeListener().onStateChanged(State.STATE_ERROR)
        }
        } finally {
            reloadFlight.set(false)
        }
    }

    fun pause(checked: Boolean) {
        val service = speechService
        if (service != null) {
            try {
                service.setPause(checked)
            } catch (_: Throwable) {
            }
            pausedState = checked
            if (checked) {
                activeListener().onStateChanged(State.STATE_PAUSED)
            } else {
                activeListener().onStateChanged(State.STATE_LISTENING)
            }
        } else {
            pausedState = false
        }
    }

    val isPaused: Boolean
        get() = pausedState && speechService != null

    fun stop(
        forceFreeRam: Boolean = false,
        announceTranscribing: Boolean = true,
        replayPendingStart: Boolean = true
    ) {
        if (!stopping.compareAndSet(false, true)) {
            Log.d(TAG, "stop() while already stopping")
            return
        }
        val listener = activeListener()
        val service = speechService
        speechService = null
        isRunning = false
        // Capture on the calling thread: currentRecognizerSource is
        // reassigned on main (locale switch) while this task waits on the
        // executor, and closing the field late could free the NEW source.
        val toClose = currentRecognizerSource
        val freeRam = forceFreeRam || !prefs.logicKeepModelInRam.get()
        AppLog.d(TAG, "stop freeRam=$freeRam")
        if (announceTranscribing && service != null && !isCurrentSourceStreaming) {
            // Record-then-transcribe sources decode inside the stop below,
            // which can take seconds: show it instead of stale UI.
            listener.onStateChanged(State.STATE_TRANSCRIBING)
        }
        // Serialized on executor: the in-flight stop (and any transcribe
        // decode inside it) and the RAM release complete before anything
        // queued later (e.g. a reload from start()).
        executor.execute {
            try {
                try {
                    service?.stop()
                } catch (_: Throwable) {
                }
                try {
                    service?.shutdown()
                } catch (_: Throwable) {
                }
            } finally {
                try {
                    toClose?.close(freeRam)
                } catch (t: Throwable) {
                    if (t is OutOfMemoryError) System.gc()
                } finally {
                    try {
                        abandonAudioFocus()
                    } catch (_: Throwable) {
                    }
                    stopping.set(false)
                    // Teardown stops (keyboard closed, recognizer session
                    // over) must not resurrect recording: only replay taps
                    // queued during a user-initiated stop.
                    val pending: Context? = pendingStartRef?.get()
                    val hadPending = hasPendingStart
                    hasPendingStart = false
                    pendingStartRef = null
                    // R12: terminal STOPPED posts from executor completion,
                    // never synchronously while decode still runs.
                    try {
                        mainHandler.post {
                            try {
                                activeListener().onStateChanged(State.STATE_STOPPED)
                            } catch (_: Throwable) {
                            }
                            if (replayPendingStart && hadPending) {
                                // Back on main: start() assumes main-thread callers.
                                start(pending)
                            }
                        }
                    } catch (_: Throwable) {
                        if (replayPendingStart && hadPending) {
                            try {
                                mainHandler.post { start(pending) }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }
        }
    }

    fun onDestroy() {
        // Service teardown (e.g. switching keyboards) is not process death:
        // when keep-in-RAM is on, leave the model loaded so a rebind
        // resurrects instantly. The OS reclaims everything on real death.
        // Never replay a queued mic tap after teardown.
        stop(
            forceFreeRam = !prefs.logicKeepModelInRam.get(),
            announceTranscribing = false,
            replayPendingStart = false
        )
    }

    /**
     * Point callbacks at a new IME instance. The manager itself is
     * process-scoped (see [SharedModelManager]): Android destroys and
     * recreates the IME service on every keyboard switch, but the loaded
     * model must survive that.
     *
     * R15: the swap is atomic; in-flight posts snapshot the reference at
     * dispatch time (copy-then-dispatch), so a swap never tears a callback.
     */
    fun attachListener(listener: Listener) {
        listenerRef.set(listener)
    }

    var recordDevice: AudioDeviceInfo? = null
        set(value) {
            field = value
            try {
                speechService?.recordDevice = value
            } catch (_: Throwable) {
            }
        }

    companion object {
        private const val TAG = "ModelManager"

        /**
         * R11: bounded caller wait for the executor resolve. Long enough
         * for a normal stat/scan burst, short enough to never ANR forever;
         * on expiry the last-known list keeps serving.
         */
        internal const val RELOAD_TIMEOUT_MS = 8_000L
        /** Auto-switch skips load-failed indices for this long. */
        internal const val LOAD_FAIL_BLACKLIST_MS = 5 * 60_000L
    }

    interface Listener : RecognitionListener {
        fun onStateChanged(state: State)

        fun onError(type: ErrorType)

        fun onRecognizerSource(source: RecognizerSource)
    }

    enum class State {
        STATE_INITIAL, STATE_LOADING, STATE_READY, STATE_LISTENING, STATE_PAUSED, STATE_ERROR, STATE_STOPPED,
        /** Non-streaming source is decoding the recorded take. */
        STATE_TRANSCRIBING
    }

    enum class ErrorType {
        MIC_IN_USE, NO_RECOGNIZERS_INSTALLED
    }
}

/**
 * Process-scoped [ModelManager]. Android destroys and recreates the IME
 * service on every keyboard switch (same pid, new instance); a per-IME
 * manager would reload the model from disk each time and orphan the
 * previous native allocation. The shared instance keeps loaded sources
 * across recreations; each new IME just re-attaches as listener.
 */
object SharedModelManager {
    private var instance: ModelManager? = null

    @Synchronized
    fun get(context: Context, listener: ModelManager.Listener): ModelManager {
        val existing = instance
        return if (existing != null) {
            existing.attachListener(listener)
            existing
        } else {
            ModelManager(context.applicationContext, listener).also {
                instance = it
            }
        }
    }
}
