package com.elishaazaria.sayboard

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.elishaazaria.sayboard.Constants.getModelsDirectory
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.SherpaEngine
import com.elishaazaria.sayboard.data.SherpaLocalModel
import com.elishaazaria.sayboard.data.VoskLocalModel
import com.elishaazaria.sayboard.data.ModelLink
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.downloader.ArchiveTools
import com.elishaazaria.sayboard.downloader.ModelFormat
import com.elishaazaria.sayboard.utils.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.*

object Tools {
    private const val TAG = "Tools"
    @JvmStatic
    fun isMicrophonePermissionGranted(activity: Activity): Boolean {
        val permissionCheck = ContextCompat.checkSelfPermission(
            activity.applicationContext,
            Manifest.permission.RECORD_AUDIO
        )
        return permissionCheck == PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun isIMEEnabled(activity: Activity): Boolean {
        val imeManager =
            activity.applicationContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        for (i in imeManager.enabledInputMethodList) {
            if (i.packageName == activity.packageName) {
                return true
            }
        }
        return false
    }

    /**
     * Redact user-specific path prefixes from native exception messages so
     * load failures stay diagnosable in exported logs without leaking the
     * storage prefix. Leaf names stay visible.
     *
     * F7 allowlist limit (stated, not silent): this covers the models base,
     * `/storage/emulated/0`, the generic `/data/` prefix (incl.
     * `/data/user/0`, `/data/data`, app and OEM subpaths), the `/mnt/`
     * prefix, and `content://` URIs. Anything outside those rules passes through in the
     * 300-char take — kept deliberately because load-phase native messages
     * (where transcript content is not expected) distinguish missing-file
     * from corrupt-file, and the leaf+kind line already sits alongside.
     */
    @JvmStatic
    fun redactErrorMessage(msg: String?): String {
        var out = (msg ?: "null").take(300)
        try {
            val ctx = runCatching { AppCtx.appCtx }.getOrNull()
            if (ctx != null) {
                val base = getModelsDirectory(ctx).canonicalPath
                out = out.replace(base, "<models>")
            }
        } catch (_: Exception) {
        }
        out = out.replace("/storage/emulated/0", "<shared>")
        out = out.replace(Regex("/data/[^\\s'\",;()]*"), "<data>")
        out = out.replace(Regex("/mnt/[^\\s'\",;()]*"), "<mnt>")
        out = out.replace(Regex("content://\\S+"), "<content>")
        return out
    }

    @JvmStatic
    fun deleteModel(model: InstalledModelReference, context: Context?) {        try {
            // U6/D17: canonical Models/ confinement — a crafted order entry
            // must never delete outside the models dir. Relative stored refs
            // resolve against the models dir first. With no context there is
            // no confinement root, so fail closed (skip + report) instead of
            // any substring fallback.
            if (context == null) {
                AppLog.e(TAG, "deleteModel refused: no modelsDir context")
                return
            }
            val modelsDir = Constants.getModelsDirectory(context)
            val modelFile = model.resolveAbsolute(modelsDir)
            com.elishaazaria.sayboard.downloader.Sanitize.requireModelsConfined(
                context, modelFile
            )
            // Canonical startsWith double-check on the resolved path.
            com.elishaazaria.sayboard.downloader.Sanitize.requireWithin(modelsDir, modelFile)
            if (modelFile.exists()) deleteRecursive(modelFile)
        } catch (e: Exception) {
            AppLog.e(TAG, "deleteModel refused/failed", e)
        }
    }

    /** On-disk size of a model directory, human-readable (e.g. "256 MB"). */
    @JvmStatic
    fun modelSizeLabel(path: String): String {
        return try {
            formatBytes(dirSize(File(path)))
        } catch (_: Exception) {
            ""
        }
    }

    /** F3: on-disk size in bytes for the pin guard (recursive walk). */
    @JvmStatic
    fun dirSizeBytes(file: File): Long = runCatching { dirSize(file) }.getOrDefault(0L)

    /**
     * F4: pin-key matching across relative (stored) / absolute (legacy)
     * forms. Absent = pinned. Never throws.
     */
    @JvmStatic
    fun isPathPinned(pins: Map<String, String>, modelsDir: java.io.File?, absPath: String): Boolean {
        if (pins.containsKey(absPath)) return false
        if (modelsDir == null) return true
        return try {
            !pins.keys.any { pinKeyMatches(modelsDir, it, absPath) }
        } catch (_: Exception) {
            true
        }
    }

    /** F4: true when a stored pin key addresses [absPath] (either form). */
    @JvmStatic
    fun pinKeyMatches(modelsDir: java.io.File, storedKey: String, absPath: String): Boolean {
        return try {
            if (storedKey == absPath) return true
            val f = java.io.File(storedKey)
            (if (f.isAbsolute) f else java.io.File(modelsDir, storedKey)).canonicalPath ==
                java.io.File(absPath).canonicalPath
        } catch (_: Exception) {
            false
        }
    }

    /**
     * F3: sum-aware soft pin guard — call OFF the main thread (recursive
     * walks + ActivityManager query). Refuses when the pinned set plus the
     * candidate would exceed half of current availMem (same budget as the
     * R3 per-load gate). Fail-open (true) when sizing is unavailable so a
     * toggle never bricks on I/O error.
     */
    @JvmStatic
    fun residentFitsRam(context: android.content.Context, candidateAbs: String, pinnedAbs: List<String>): Boolean {
        return try {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val avail = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
            if (avail <= 0) return true
            var total = dirSizeBytes(java.io.File(candidateAbs))
            for (p in pinnedAbs) {
                if (p != candidateAbs) total += dirSizeBytes(java.io.File(p))
            }
            val ok = total <= avail / 2
            if (!ok) AppLog.e(TAG, "pin refused on RAM grounds")
            ok
        } catch (_: Exception) {
            true
        }
    }

    private fun dirSize(file: File): Long {        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        for (child in file.listFiles() ?: return total) {
            total += dirSize(child)
        }
        return total
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return if (value >= 100) "${value.toInt()} ${units[unit]}"
        else "${(value * 10).toInt() / 10.0} ${units[unit]}"
    }

    @JvmStatic
    fun deleteRecursive(fileOrDirectory: File, deleteStartingFolder: Boolean = true) {
        if (fileOrDirectory.isDirectory) for (child in fileOrDirectory.listFiles() ?: emptyArray())
            deleteRecursive(child, true)
        if (deleteStartingFolder) {
            fileOrDirectory.delete()
        }
    }

    /**
     * D17/U6: resolve an installed Vosk reference to its model dir, confined
     * to [modelsDir] via canonical startsWith. Relative stored refs resolve
     * against [modelsDir]; legacy absolute refs are confined as-is. A null
     * [modelsDir] has no confinement root, so resolution fails closed (null).
     * Callers with a Context should pass `Constants.getModelsDirectory(ctx)`.
     */
    @JvmStatic
    fun getVoskModelFromReference(
        reference: InstalledModelReference,
        modelsDir: File? = null
    ): VoskLocalModel? {
        // Resolve the exact recorded path first: scanning the locale folder
        // and returning the first directory silently picks the wrong model
        // when several Vosk models share a locale.
        if (modelsDir == null) {
            AppLog.e(TAG, "vosk resolve refused: no modelsDir context")
            return null
        }
        val exact = try {
            val abs = reference.resolveAbsolute(modelsDir)
            com.elishaazaria.sayboard.downloader.Sanitize.requireWithin(modelsDir, abs)
            abs
        } catch (_: Exception) {
            AppLog.e(TAG, "vosk resolve refused: outside Models/")
            return null
        }
        if (exact.isDirectory) {
            val locale = Locale.forLanguageTag(exact.parentFile?.name ?: "")
            return VoskLocalModel(exact.absolutePath, locale, exact.name, reference.alias)
        }
        // Fallback for moved installs: first directory under the locale
        // folder, null-safe. The locale folder itself must stay confined.
        val localeFolder = exact.parentFile ?: return null
        try {
            com.elishaazaria.sayboard.downloader.Sanitize.requireWithin(modelsDir, localeFolder)
        } catch (_: Exception) {
            return null
        }
        val locale = Locale.forLanguageTag(localeFolder.name)
        for (modelFolder in localeFolder.listFiles() ?: return null) {
            if (!modelFolder.isDirectory) continue
            try {
                com.elishaazaria.sayboard.downloader.Sanitize.requireWithin(modelsDir, modelFolder)
            } catch (_: Exception) {
                continue
            }
            return VoskLocalModel(
                modelFolder.absolutePath, locale, modelFolder.name, reference.alias
            )
        }
        return null
    }

    /**
     * Resolve an installed sherpa-onnx reference to its model dir, engine and
     * locale. Detection is content-based (file layout, streaming-checkpoint
     * markers), so renames and stale markers heal on the next scan; a fresh
     * streaming detection also repairs the marker.
     *
     * D17/U6: [modelsDir] confinement, same contract as
     * [getVoskModelFromReference] — null fails closed.
     */
    @JvmStatic
    fun getSherpaModelFromReference(
        reference: InstalledModelReference,
        modelsDir: File? = null
    ): SherpaLocalModel? {
        if (modelsDir == null) {
            AppLog.e(TAG, "sherpa resolve refused: no modelsDir context")
            return null
        }
        val target = try {
            val abs = reference.resolveAbsolute(modelsDir)
            com.elishaazaria.sayboard.downloader.Sanitize.requireWithin(modelsDir, abs)
            abs
        } catch (_: Exception) {
            AppLog.e(TAG, "sherpa resolve refused: outside Models/")
            return null
        }
        return getSherpaModelFromPath(target, reference.alias, modelsDir)
    }

    /**
     * Same as [getSherpaModelFromReference] but from a directory, for
     * scans that do not have a reference yet (no dummy-type probing).
     * When [modelsDir] is supplied the folder is confined first; scans
     * already check containment, so null keeps scan behavior.
     */
    @JvmStatic
    fun getSherpaModelFromPath(
        modelFolder: File,
        alias: String = "",
        modelsDir: File? = null
    ): SherpaLocalModel? {
        if (modelsDir != null) {
            try {
                com.elishaazaria.sayboard.downloader.Sanitize.requireWithin(modelsDir, modelFolder)
            } catch (_: Exception) {
                AppLog.e(TAG, "sherpa path refused: outside Models/")
                return null
            }
        }
        if (!modelFolder.isDirectory) return null
        val localeFolder = modelFolder.parentFile ?: return null
        val locale = Locale.forLanguageTag(localeFolder.name)
        val engine = when (ArchiveTools.detectFormat(modelFolder)) {
            ModelFormat.WHISPER -> SherpaEngine.WHISPER
            ModelFormat.PARAKEET ->
                if (ArchiveTools.readEngineMarker(modelFolder) == SherpaEngine.STREAMING ||
                    ArchiveTools.isStreamingCheckpoint(modelFolder)
                ) {
                    ArchiveTools.writeEngineMarker(modelFolder, SherpaEngine.STREAMING)
                    SherpaEngine.STREAMING
                } else {
                    SherpaEngine.PARAKEET
                }
            else -> ArchiveTools.readEngineMarker(modelFolder) ?: return null
        }
        // D19: counts/engines only in the app log; no leaf/basename. Engine
        // detail stays behind DEBUG.
        if (BuildConfig.DEBUG) AppLog.d(TAG, "resolve ok engine=$engine")
        return SherpaLocalModel(
            modelFolder.absolutePath, locale, modelFolder.name, engine, alias
        )
    }

    fun createNotificationChannel(context: Context) {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = context.getString(R.string.notification_download_channel_name)
            val description = context.getString(R.string.notification_download_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(Constants.DOWNLOADER_CHANNEL_ID, name, importance)
            channel.description = description
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            val notificationManager = context.getSystemService(
                NotificationManager::class.java
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun copyStreamToFile(inputStream: InputStream, outputFile: File) {
        if (!outputFile.parentFile!!.exists()) {
            outputFile.parentFile!!.mkdirs()
        }
        inputStream.use { input ->
            val outputStream = FileOutputStream(outputFile)
            outputStream.use { output ->
                val buffer = ByteArray(4 * 1024) // buffer size
                while (true) {
                    val byteCount = input.read(buffer)
                    if (byteCount < 0) break
                    output.write(buffer, 0, byteCount)
                }
                output.flush()
            }
        }
    }
}