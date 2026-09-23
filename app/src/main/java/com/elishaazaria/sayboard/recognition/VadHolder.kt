package com.elishaazaria.sayboard.recognition

import android.content.Context
import android.util.Log
import com.elishaazaria.sayboard.downloader.ArchiveTools
import com.elishaazaria.sayboard.utils.AppLog
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.security.MessageDigest

/**
 * Lazily provides the bundled Silero VAD model (assets/silero_vad.onnx),
 * copied once to internal storage. Null when unavailable; callers must
 * treat VAD as optional.
 *
 * Tunables (KDoc per R16):
 * - [VAD_THRESHOLD]: speech probability gate (0.5). Higher = fewer false
 *   wakeups, lower = catches quiet speech.
 * - [MIN_SPEECH_DURATION_SEC] (0.25s): speech must persist this long before
 *   [Vad.isSpeechDetected] flips true; filters clicks/pops.
 * - [windowSize] (512 @16kHz = 32ms): native frame; fixed by the Silero
 *   export, not user-tunable.
 * - [numThreads] (1) / [provider] ("cpu"): VAD runs per audio buffer on the
 *   mic thread; keep single-threaded CPU so it never contends with decode.
 * - [STAGE_RETRY_MS] (60s): transient stage failures retry after a minute
 *   instead of disabling VAD until process death.
 * - [VAD_MAX_BYTES] (32MB): staged-file sanity cap; the bundled model is
 *   <1MB, so anything larger is a corrupt/tampered stage.
 */
object VadHolder {
    private const val TAG = "VadHolder"
    private const val ASSET_NAME = "silero_vad.onnx"
    private const val SHA_ASSET_NAME = "silero_vad.onnx.sha256"
    private const val STAGE_RETRY_MS = 60_000L
    /** Staged-file sanity cap: bundled VAD is <1MB. */
    internal const val VAD_MAX_BYTES = 32L * 1024 * 1024
    /** Speech probability gate; see class KDoc. */
    internal const val VAD_THRESHOLD = 0.5f
    /** Minimum speech span before detection flips true. */
    internal const val MIN_SPEECH_DURATION_SEC = 0.25f

    @Volatile
    private var modelPath: String? = null

    /**
     * Last staging failure epoch-ms, 0 when healthy. A transient failure
     * (full disk, killed mid-copy) retries after a minute instead of
     * disabling VAD until process death.
     */
    private var failedAt: Long = 0

    @Synchronized
    fun modelPath(context: Context): String? {
        val app = context.applicationContext
        modelPath?.let {
            val f = File(it)
            if (f.isFile && f.length() > 0 && f.length() <= VAD_MAX_BYTES && verifyStaged(app, f)) {
                return it
            }
            // Stale/corrupt stage: drop the cache and restage below.
            modelPath = null
            try {
                f.delete()
            } catch (_: Throwable) {
            }
        }
        if (failedAt != 0L && System.currentTimeMillis() - failedAt < STAGE_RETRY_MS) {
            return null
        }
        return try {
            val out = File(File(app.filesDir, "sherpa"), ASSET_NAME)
            if (!out.isFile || out.length() <= 0L || out.length() > VAD_MAX_BYTES ||
                !verifyStaged(app, out)
            ) {
                stageFromAssets(app, out) ?: return null
            }
            modelPath = out.absolutePath
            AppLog.d(TAG, "vad model staged bytes=${out.length()}")
            out.absolutePath
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "vad stage OOM", e)
            AppLog.e(TAG, "vad stage OOM bytes=0")
            failedAt = System.currentTimeMillis()
            modelPath = null
            null
        } catch (t: Throwable) {
            Log.e(TAG, "vad model unavailable", t)
            AppLog.e(TAG, "vad model unavailable")
            failedAt = System.currentTimeMillis()
            null
        }
    }

    /**
     * Stage the asset via .tmp + atomic rename and verify its SHA-256
     * against the shipped .sha256 asset. Returns the staged file, or null
     * on any failure (partial files are deleted, never left in place).
     */
    private fun stageFromAssets(app: Context, out: File): File? {
        try {
            out.parentFile?.mkdirs()
        } catch (_: Throwable) {
            failedAt = System.currentTimeMillis()
            return null
        }
        val tmp = File(out.parentFile, "$ASSET_NAME.tmp")
        try {
            tmp.delete()
        } catch (_: Throwable) {
        }
        try {
            app.assets.open(ASSET_NAME).use { ins ->
                tmp.outputStream().use { os ->
                    ins.copyTo(os)
                }
            }
            if (!verifyStaged(app, tmp)) {
                try {
                    tmp.delete()
                } catch (_: Throwable) {
                }
                AppLog.e(TAG, "vad sha mismatch")
                failedAt = System.currentTimeMillis()
                return null
            }
            if (!tmp.renameTo(out)) {
                // Rename failed: never leave a non-atomic overwrite behind.
                try {
                    tmp.delete()
                } catch (_: Throwable) {
                }
                failedAt = System.currentTimeMillis()
                return null
            }
            return out
        } catch (t: Throwable) {
            try {
                tmp.delete()
            } catch (_: Throwable) {
            }
            if (t is OutOfMemoryError) throw t
            Log.e(TAG, "vad stage failed", t)
            AppLog.e(TAG, "vad stage failed")
            failedAt = System.currentTimeMillis()
            return null
        }
    }

    /** True when [file]'s SHA-256 matches the shipped .sha256 asset. */
    private fun verifyStaged(app: Context, file: File): Boolean {
        if (!file.isFile || file.length() <= 0L || file.length() > VAD_MAX_BYTES) return false
        val expected = expectedSha(app) ?: return false
        val actual = try {
            sha256Of(file)
        } catch (_: Throwable) {
            null
        } ?: return false
        return actual.equals(expected, ignoreCase = true)
    }

    private fun expectedSha(app: Context): String? {
        return try {
            app.assets.open(SHA_ASSET_NAME).bufferedReader().use { it.readText() }
                .trim().split("\\s+".toRegex()).firstOrNull()?.lowercase()
                ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun sha256Of(file: File): String? {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Fresh VAD instance per recording; caller must release().
     * [minSilenceDurationSec] is the single trailing-silence control:
     * callers must not stack their own silence counter on top of it.
     *
     * Pre-flight (R1): staged file must exist, be non-empty and under
     * [VAD_MAX_BYTES], and pass the protobuf header sniff before native
     * construction ([ArchiveTools.isPlausibleOnnxHead] — the head-only
     * variant, since the bundled model is under the 1MB ONNX size floor);
     * construction is wrapped in catch(Throwable) with an OOM guard
     * returning null (VAD stays optional, never stuck LOADING).
     */
    fun createVad(context: Context, minSilenceDurationSec: Float = 1.0f): Vad? {
        val app = context.applicationContext
        val model = modelPath(app) ?: return null
        try {
            val f = File(model)
            if (!f.isFile || f.length() <= 0L || f.length() > VAD_MAX_BYTES) {
                AppLog.e(TAG, "vad preflight rejected bytes=${runCatching { f.length() }.getOrDefault(-1)}")
                return null
            }
            if (!ArchiveTools.isPlausibleOnnxHead(f)) {
                AppLog.e(TAG, "vad magic refused bytes=${runCatching { f.length() }.getOrDefault(-1)}")
                return null
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            AppLog.e(TAG, "vad preflight failed")
            return null
        }
        return try {
            Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = model,
                        threshold = VAD_THRESHOLD,
                        minSilenceDuration = minSilenceDurationSec,
                        minSpeechDuration = MIN_SPEECH_DURATION_SEC,
                        windowSize = 512
                    ),
                    sampleRate = 16000,
                    numThreads = 1,
                    provider = "cpu"
                )
            )
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "vad init OOM", e)
            AppLog.e(TAG, "vad init OOM")
            System.gc()
            null
        } catch (t: Throwable) {
            Log.e(TAG, "vad init failed", t)
            AppLog.e(TAG, "vad init failed")
            null
        }
    }

    /**
     * Pre-warm the staged VAD model off the audio thread (R11). Invoke from
     * the model-load path (e.g. [com.elishaazaria.sayboard.recognition.ModelManager]
     * initialize on its executor), never from the mic thread: stages the
     * file and builds + releases one Vad so the first real take pays no
     * copy/init cost.
     */
    fun prewarm(context: Context) {
        try {
            val app = context.applicationContext
            if (modelPath(app) == null) return
            try {
                createVad(app)?.release()
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }
}
