package com.elishaazaria.sayboard.utils

import android.content.Context
import android.util.Log
import com.elishaazaria.sayboard.BuildConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Exhaustive app log persisted to the app-private filesDir/SayboardLogs
 * directory (U1: never Download/ or world-readable), so failures can be
 * retrieved without adb. Also mirrors to logcat in DEBUG builds only (U2).
 * Writes go through a single background thread; [eNow] writes inline for
 * use on crashing threads.
 *
 * Messages are redacted before persisting: URLs, absolute paths (leaf
 * names only) and package-like tokens never reach the file.
 */
object AppLog {
    private const val TAG = "AppLog"
    private const val LOG_DIR = "SayboardLogs"
    private const val PREFIX = "sayboard-"
    private const val KEEP_FILES = 5

    private val executor = Executors.newSingleThreadExecutor()
    // SimpleDateFormat is not thread-safe and format() runs on caller
    // threads: one instance per thread.
    private val dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    // Single buffered stream per session instead of one FD open per line.
    // Guarded by writeLock; flush per line keeps crash visibility.
    private val writeLock = Any()
    private var writer: OutputStream? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sessionFile: File? = null

    /** U1 opt-out (`logicFileLogging`, default true). File off, logcat stays. */
    @Volatile
    var fileLoggingEnabled: Boolean = true

    /** In-memory tail (newest last), capped, for potential in-app viewing. */
    private val ring = ArrayDeque<String>()
    private const val RING_CAP = 500

    fun init(context: Context) {
        appContext = context.applicationContext
        executor.execute {
            try {
                rotate()
                openSessionFile()
                d(TAG, "=== Sayboard ${com.elishaazaria.sayboard.BuildConfig.VERSION_NAME} " +
                    "v${com.elishaazaria.sayboard.BuildConfig.VERSION_CODE} ===")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "init failed", e)
            }
        }
    }

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
        enqueue("D", tag, msg, null)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(tag, msg, tr)
        enqueue("E", tag, msg, tr)
    }

    /** Synchronous write for crashing threads; best-effort. */
    fun eNow(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.e(tag, msg)
        try {
            writeLine(format("E", tag, msg, null))
        } catch (_: Exception) {
        }
    }

    private fun enqueue(level: String, tag: String, msg: String, tr: Throwable?) {
        val line = format(level, tag, msg, tr)
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > RING_CAP) ring.removeFirst()
        }
        if (!fileLoggingEnabled) return
        executor.execute {
            try {
                writeLine(line)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "write failed", e)
            }
        }
    }

    /**
     * U1 redaction: leaf names only, no URLs/paths/packages/transcripts.
     * Applied to every persisted line (logcat mirrors in DEBUG are raw).
     */
    fun redact(msg: String): String {
        var out = msg
        // URLs first (they contain slashes that the path rule would shred).
        out = out.replace(Regex("https?://\\S+"), "[url]")
        // Absolute/relative paths -> leaf name only.
        out = out.replace(Regex("(?<=\\s|^|['\"(])/[^\\s'\",;()]*\\/([^\\s'\",;()/]+)")) { it.groupValues[1] }
        out = out.replace(Regex("(?<=\\s|^|['\"(])/[^\\s'\",;()]+")) { it.value.substringAfterLast('/') }
        // Windows-style paths.
        out = out.replace(Regex("[A-Za-z]:\\\\[^\\s'\",;()]*")) { it.value.substringAfterLast('\\') }
        // Java package-like tokens (a.b.C) that are not plain words.
        // Exempt fixed-vocabulary model filenames: leaves are visible by
        // design and these carry zero PII, while a bare exemption would
        // otherwise eat every dotted filename.
        out = out.replace(Regex("\\b[a-z][A-Za-z0-9_]*(\\.[a-zA-Z][A-Za-z0-9_]*)+\\b")) {
            val v = it.value
            if (isModelFilename(v)) v else "[pkg]"
        }
        return out
    }

    /**
     * F6: fixed-vocabulary model filenames exempt from [pkg] redaction.
     * Exact names (case-insensitive) for files with generic suffixes, plus
     * only unambiguous model suffixes (variable encoder/decoder names need
     * suffix matching). Everything else — explicitly including `.pt`,
     * `.txt`, `.json`, `.bin`, `.zip`, `.bz2`, `.gz`, `.conf` — redacts.
     * Cost accepted: fixed-name diagnostics like `phones.txt`/`mfcc.conf`
     * now redact; counts and leaf-gated lines remain.
     */
    private val MODEL_EXACT_NAMES = setOf("final.mdl", "gr.fst", "tokens.txt")
    private val MODEL_SUFFIXES = setOf(".fst", ".mdl", ".onnx", ".onnxt")

    private fun isModelFilename(token: String): Boolean {
        if (MODEL_EXACT_NAMES.any { token.equals(it, ignoreCase = true) }) return true
        return MODEL_SUFFIXES.any { token.endsWith(it, ignoreCase = true) }
    }

    private fun format(level: String, tag: String, msg: String, tr: Throwable?): String {
        val sb = StringBuilder()
        sb.append(dateFmt.get().format(Date())).append(' ')
            .append(level).append('/').append(tag).append(": ").append(redact(msg))
        if (tr != null) {
            sb.append('\n').append(redact(Log.getStackTraceString(tr)))
        }
        return sb.toString()
    }

    private fun logDir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, LOG_DIR)
    }

    private fun writeLine(line: String) {
        if (!fileLoggingEnabled) return
        val file = sessionFile ?: return
        synchronized(writeLock) {
            try {
                var w = writer
                if (w == null) {
                    file.parentFile?.mkdirs()
                    w = BufferedOutputStream(FileOutputStream(file, true), 8192)
                    writer = w
                }
                w.write((line + "\n").toByteArray())
                w.flush()
            } catch (_: Exception) {
                try {
                    writer?.close()
                } catch (_: Exception) {
                }
                writer = null
            }
        }
    }

    private fun openSessionFile() {
        val dir = logDir() ?: return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        sessionFile = File(dir, "$PREFIX$stamp.log")
        // New session file: drop the previous session's stream.
        synchronized(writeLock) {
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
        }
    }

    private fun rotate() {
        try {
            val dir = logDir() ?: return
            val files = dir.listFiles { f ->
                f.name.startsWith(PREFIX) && f.name.endsWith(".log")
            }?.sortedBy { it.lastModified() } ?: return
            for (f in files.dropLast(KEEP_FILES - 1)) {
                try {
                    f.delete()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "rotate failed", e)
        }
    }

    /** U1 "Export log" source: newest session files, oldest first. */
    fun exportFiles(): List<File> {
        return try {
            val dir = logDir() ?: return emptyList()
            dir.listFiles { f ->
                f.name.startsWith(PREFIX) && f.name.endsWith(".log")
            }?.sortedBy { it.lastModified() }?.takeLast(KEEP_FILES) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
