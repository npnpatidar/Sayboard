package com.elishaazaria.sayboard.recognition.recognizers.sources

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.elishaazaria.sayboard.AppCtx
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.data.VoskLocalModel
import com.elishaazaria.sayboard.downloader.ArchiveTools
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import com.elishaazaria.sayboard.utils.AppLog
import org.json.JSONException
import org.json.JSONObject
import org.vosk.Model
import java.io.File
import java.util.concurrent.Executor
import java.util.Locale

/**
 * Vosk (streaming) recognizer source.
 *
 * Tunables (R16):
 * - Sample rate is fixed at 16kHz by the Kaldi model export.
 * - [VOSK_MAX_BYTES] (400MB): pre-flight sanity cap on the model dir;
 *   real small models are tens of MB.
 * - Memory pre-flight (R3) refuses the load when the dir size exceeds
 *   half of [ActivityManager.MemoryInfo.availMem].
 * - `cpu`/`debug=false` equivalents are Vosk-internal; no GPU path is
 *   enabled.
 *
 * Ownership (R7): native [Model]/[MyRecognizer] are released exactly once
 * inside [closeLock] with try/finally; [released] makes close idempotent.
 */
class VoskLocal(private val localModel: VoskLocalModel) : RecognizerSource {
    private val stateMLD = MutableLiveData(RecognizerState.NONE)
    override val stateLD: LiveData<RecognizerState>
        get() = stateMLD
    private var myRecognizer: MyRecognizer? = null
    override val recognizer: Recognizer
        get() = myRecognizer!!
    private var model: Model? = null
    /** Set by close(true): a load still in flight must release instead of publishing. */
    @Volatile
    private var closeRequested = false
    private val closeLock = Any()
    @Volatile
    private var released = false
    private val pendingCallbacks = mutableListOf<Observer<RecognizerSource?>>()
    override fun initialize(executor: Executor, onLoaded: Observer<RecognizerSource?>) {
        synchronized(pendingCallbacks) {
            // Already loaded (e.g. kept in RAM): no disk reload. A load in
            // flight: piggyback instead of stacking another load.
            if (myRecognizer != null) {
                stateMLD.postValue(RecognizerState.READY)
                onLoaded.onChanged(this)
                return
            }
            if (stateMLD.value == RecognizerState.LOADING) {
                pendingCallbacks.add(onLoaded)
                return
            }
            pendingCallbacks.add(onLoaded)
        }
        synchronized(closeLock) {
            released = false
        }
        closeRequested = false
        stateMLD.postValue(RecognizerState.LOADING)
        val handler = Handler(Looper.getMainLooper())
        executor.execute {
            // A failed load must unblock piggybacked callers (same shape as
            // the Sherpa sources): otherwise state sticks at LOADING forever
            // and every later initialize() hangs on dead callbacks.
            try {
                preflightOrThrow()
                val model = Model(localModel.path)
                handler.post {
                    if (closeRequested) {
                        // Freed while loading: release instead of publishing
                        // an orphaned native model.
                        try {
                            model.close()
                        } catch (_: Throwable) {
                        }
                        stateMLD.postValue(RecognizerState.ERROR)
                        fireLoaded(null)
                    } else {
                        synchronized(closeLock) {
                            released = false
                        }
                        modelLoaded(model)
                        fireLoaded(this)
                    }
                }
            } catch (e: OutOfMemoryError) {
                System.gc()
                AppLog.e(TAG, "vosk load OOM leaf=${leafName()}")
                handler.post {
                    stateMLD.postValue(RecognizerState.ERROR)
                    fireLoaded(null)
                }
            } catch (t: Throwable) {
                // R1: native aborts/LinkError/OOM must reach ERROR, never
                // stuck LOADING. Log leaf name + kind only (no paths).
                android.util.Log.e(TAG, "vosk load failed leaf=${leafName()} kind=${t.javaClass.simpleName}")
                AppLog.e(TAG, "vosk load failed leaf=${leafName()} kind=${t.javaClass.simpleName} msg=${com.elishaazaria.sayboard.Tools.redactErrorMessage(t.message)}")
                handler.post {
                    stateMLD.postValue(RecognizerState.ERROR)
                    fireLoaded(null)
                }
            }
        }
    }

    /**
     * R1+R3 pre-flight: exact final.mdl marker (+ am/), Kaldi header sniff,
     * sane total size, and an ActivityManager memory gate. Throws on any
     * refusal (fail fast before native construction, user-visible via the
     * ERROR state + [errorMessage]).
     */
    @Throws(Exception::class)
    private fun preflightOrThrow() {
        val dir = File(localModel.path)
        if (!dir.isDirectory) throw java.io.IOException("vosk dir missing")
        val amFinal = File(dir, "am/final.mdl")
        val topFinal = File(dir, "final.mdl")
        val amDir = File(dir, "am")
        val hasMarker = amFinal.isFile || (topFinal.isFile && amDir.isDirectory)
        if (!hasMarker) throw java.io.IOException("vosk marker missing")
        val finalMdl = if (amFinal.isFile) amFinal else topFinal
        if (!ArchiveTools.isPlausibleKaldiMdl(finalMdl)) {
            AppLog.e(TAG, "vosk magic refused leaf=${leafName()}")
            throw java.io.IOException("vosk magic refused")
        }
        var total = 0L
        try {
            // Zero-length files are skipped, not refused: upstream ships
            // legitimate 0-byte configs (e.g. ivector/online_cmvn.conf in
            // vosk-model-small-hi-0.22). Bomb protection rests on the entry
            // count cap + total-size ceiling; completeness is enforced
            // authoritatively by the native loader below.
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val len = f.length()
                    if (len <= 0L) return@forEach
                    total += len
                    if (total > VOSK_MAX_BYTES) throw java.io.IOException("vosk oversize")
                }
            }
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (t: Throwable) {
            if (t is java.io.IOException) throw t
            throw java.io.IOException("vosk scan failed leaf=${dir.name.take(80)}")
        }
        if (total <= 0L || total > VOSK_MAX_BYTES) {
            throw java.io.IOException("vosk size refused bytes=$total")
        }
        if (!hasMemoryFor(total)) {
            AppLog.e(TAG, "vosk memory refused bytes=$total leaf=${leafName()}")
            throw java.io.IOException("vosk memory refused")
        }
    }

    /** R3: refuse when the model exceeds half of availMem. */
    private fun hasMemoryFor(totalBytes: Long): Boolean {
        return try {
            val app: Context = AppCtx.appCtx ?: return totalBytes <= VOSK_MAX_BYTES
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            totalBytes <= info.availMem / 2
        } catch (_: Throwable) {
            totalBytes <= VOSK_MAX_BYTES
        }
    }

    private fun leafName(): String {
        return try {
            File(localModel.path).name.take(80)
        } catch (_: Throwable) {
            "model"
        }
    }

    private fun fireLoaded(source: RecognizerSource?) {
        val callbacks: List<Observer<RecognizerSource?>>
        synchronized(pendingCallbacks) {
            callbacks = pendingCallbacks.toList()
            pendingCallbacks.clear()
        }
        callbacks.forEach { it.onChanged(source) }
    }

    override val closed: Boolean
        get() = myRecognizer == null
    override val addSpaces: Boolean
        get() = !listOf("ja", "zh").contains(localModel.locale.language)

    private fun modelLoaded(model: Model) {
        this.model = model
        stateMLD.postValue(RecognizerState.READY)
        myRecognizer = MyRecognizer(model, 16000.0f, localModel.locale)
    }

    private class MyRecognizer     //            setMaxAlternatives(3); // TODO: implement
        (model: Model, override val sampleRate: Float, override val locale: Locale?) :
        org.vosk.Recognizer(model, sampleRate),
        Recognizer {
        private var parseFailures = 0

        override fun getResult(): String {
            try {
                val result = JSONObject(super.getResult())
                parseFailures = 0
                return removeSpaceForLocale(result.getString("text").trim { it <= ' ' })
            } catch (e: JSONException) {
                onParseFailure(e)
            } catch (t: Throwable) {
                onParseFailure(t)
            }
            return ""
        }

        override fun getPartialResult(): String {
            try {
                val result = JSONObject(super.getPartialResult())
                parseFailures = 0
                return removeSpaceForLocale(result.getString("partial").trim { it <= ' ' })
            } catch (e: JSONException) {
                onParseFailure(e)
            } catch (t: Throwable) {
                onParseFailure(t)
            }
            return ""
        }

        override fun getFinalResult(): String {
            try {
                val result = JSONObject(super.getFinalResult())
                parseFailures = 0
                return removeSpaceForLocale(result.getString("text").trim { it <= ' ' })
            } catch (e: JSONException) {
                onParseFailure(e)
            } catch (t: Throwable) {
                onParseFailure(t)
            }
            return ""
        }

        /**
         * R14: consecutive-failure counter matching the offline path
         * ([SherpaOfflineRecognizer]: [PERSISTENT_FAILURES] consecutive
         * failures, reset on success). Persistent failures throw so they
         * route through the same mechanism other sources use to reach the
         * service counter ([com.elishaazaria.sayboard.recognition.MySpeechService]
         * catches recognizer throws and escalates to onError); transient
         * ones still return "". True-empty results parse successfully, so
         * they reset the counter and return "" without counting.
         */
        private fun onParseFailure(t: Throwable) {
            parseFailures++
            if (t is OutOfMemoryError) System.gc()
            // Redacted: counts only, never hypothesis text.
            if (parseFailures >= PERSISTENT_FAILURES) {
                com.elishaazaria.sayboard.utils.AppLog.e(
                    "VoskParse",
                    "parse persistent failures=$parseFailures kind=${t.javaClass.simpleName}"
                )
                throw java.io.IOException("vosk parse failed persistently")
            }
            com.elishaazaria.sayboard.utils.AppLog.e(
                "VoskParse",
                "parse failed failures=$parseFailures kind=${t.javaClass.simpleName}"
            )
        }

        companion object {
            /**
             * Same threshold/policy as the offline path
             * ([SherpaOfflineRecognizer.PERSISTENT_FAILURES], private there
             * so the value is mirrored, not referenced).
             */
            private const val PERSISTENT_FAILURES = 3
        }
    }

    /**
     * Single-ownership native release (R7): idempotent [released] flag,
     * release inside [closeLock] with try/finally, never double-releases.
     * [MyRecognizer] and [Model] are distinct natives, each closed once.
     */
    override fun close(freeRAM: Boolean) {
        if (!freeRAM) return
        synchronized(closeLock) {
            if (released) return
            released = true
            closeRequested = true
            val r = myRecognizer
            myRecognizer = null
            val m = model
            model = null
            try {
                try {
                    r?.close()
                } catch (_: Throwable) {
                }
            } finally {
                try {
                    m?.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    override val errorMessage: Int
        get() = R.string.mic_error_recognizer_error
    override val name: String
        get() = localModel.alias.ifBlank { localModel.locale.displayName ?: "" }
    override val locale: Locale
        get() = localModel.locale

    companion object {
        private const val TAG = "VoskLocal"
        /** Pre-flight sanity cap: small Vosk models are tens of MB. */
        internal const val VOSK_MAX_BYTES = 400L * 1024 * 1024
    }
}
