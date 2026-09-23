package com.elishaazaria.sayboard.downloader

import android.content.Context
import android.util.Log
import androidx.lifecycle.Observer
import com.elishaazaria.sayboard.BuildConfig
import com.elishaazaria.sayboard.Constants
import com.elishaazaria.sayboard.Tools.deleteRecursive
import com.elishaazaria.sayboard.utils.AppLog
import java.io.*
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

object ZipTools {
    private const val TAG = "ZipTools"
    private val localePattern: Pattern =
        Pattern.compile("vosk-model-(small-)?(\\w\\w(-\\w\\w)?)-(\\w+-)?v?\\d\\.?\\d*.*")

    /**
     * D3: unified [Quota.Tracker] (entries/total/per-entry/ratio), throttled
     * per-entry progress (0.5% steps), cancel checks. Returns false (after
     * errorObserver) instead of throwing for unrecognized content; throws for
     * I/O/quota/cancel failures. D5: callers must not post success after false.
     */
    @Throws(IOException::class)
    fun unzip(
        archive: File,
//        tempUnzipLocation: File,
//        unzipFinalDestination: File,
        definedLocale: Locale = Constants.UndefinedLocale,
        context: Context,
        errorObserver: Observer<String>? = null,
        progressObserver: Observer<Double>,
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        var locale = definedLocale
        val tempUnzipLocation = Constants.getTemporaryUnzipLocation(context)

        tempUnzipLocation.parentFile?.mkdirs()

        if (tempUnzipLocation.exists()) {
            deleteRecursive(tempUnzipLocation)
        }
        Quota.checkFreeSpace(tempUnzipLocation.parentFile ?: context.cacheDir, 0)
        val zipfile = try {
            ZipFile(archive)
        } catch (e: ZipException) {
            errorObserver?.onChanged("Not a zip archive (${e.message})")
            return false
        } catch (e: IOException) {
            errorObserver?.onChanged("Cannot read archive (${e.message})")
            return false
        }
        val tracker = Quota.Tracker(archive.length())
        try {
            zipfile.use { zf ->
                val size = zf.size().toDouble().coerceAtLeast(1.0)
                var i = 0
                var lastReported = -1.0
                val e = zf.entries()
                while (e.hasMoreElements()) {
                    if (isCancelled()) throw IOException("Canceled")
                    val entry = e.nextElement() as ZipEntry
                    tracker.onEntry(if (entry.size >= 0) entry.size else -1L)
                    if (locale == Constants.UndefinedLocale) {
                        val matcher = localePattern.matcher(entry.name)
                        if (matcher.matches()) {
                            val tag = matcher.group(2)
                            if (tag != null) {
                                try {
                                    locale = Locale.forLanguageTag(tag)
                                } catch (_: Exception) {
                                }
                                if (BuildConfig.DEBUG) Log.d(TAG, "Locale detected")
                            }
                        }
                    }
                    unzipEntry(zf, entry, tempUnzipLocation.absolutePath, tracker, isCancelled)
                    i++
                    val d = (i / size).coerceIn(0.0, 1.0)
                    if (d - lastReported >= 0.005 || d >= 1.0) {
                        lastReported = d
                        progressObserver.onChanged(d)
                    }
                }
            }
        } catch (e: IOException) {
            deleteRecursive(tempUnzipLocation)
            throw e
        }

        val scan = ArchiveTools.scan(tempUnzipLocation)
        val format = scan.format()
        if (BuildConfig.DEBUG) Log.d(TAG, "Unzipped scan: ${scan.countSummary()}")
        if (format == ModelFormat.UNKNOWN) {
            // Not a Vosk or sherpa-onnx (Whisper/Parakeet) model!
            Log.e(TAG, "Not a recognized model")
            errorObserver?.onChanged("Not a recognized model (${scan.summary()})")
            deleteRecursive(tempUnzipLocation)
            return false
        }

        return finishInstall(tempUnzipLocation, format, locale, context, errorObserver)
    }

    /**
     * Shared tail of model installation: move the extracted tree into
     * Models/<locale>/ and stamp sherpa models with their engine marker.
     * Returns true on success; false after errorObserver (D5: caller must
     * throw, never post FINISHED). D17: the destination is confinement-checked
     * against Models/ before anything moves.
     */
    fun finishInstall(
        tempUnzipLocation: File,
        format: ModelFormat,
        locale: Locale,
        context: Context,
        errorObserver: Observer<String>? = null
    ): Boolean {
        val unzipFinalDestination = Constants.getDirectoryForModel(
            context, locale
        )
        try {
            Sanitize.requireModelsConfined(context, unzipFinalDestination)
        } catch (e: IOException) {
            errorObserver?.onChanged(e.message ?: "unzip failed")
            deleteRecursive(tempUnzipLocation)
            return false
        }

        // Stamp the engine marker BEFORE moving: after a merge the
        // destination holds older models too, and "first child" would be
        // the wrong directory.
        if (format == ModelFormat.WHISPER || format == ModelFormat.PARAKEET) {
            val freshTopDir = ArchiveTools.findModelTopDir(tempUnzipLocation)
            if (freshTopDir != null) {
                val engine = if (format == ModelFormat.WHISPER) {
                    com.elishaazaria.sayboard.data.SherpaEngine.WHISPER
                } else if (ArchiveTools.isStreamingCheckpoint(freshTopDir)) {
                    com.elishaazaria.sayboard.data.SherpaEngine.STREAMING
                } else {
                    com.elishaazaria.sayboard.data.SherpaEngine.PARAKEET
                }
                ArchiveTools.writeEngineMarker(freshTopDir, engine)
                if (BuildConfig.DEBUG) Log.d(TAG, "install marker $engine")
            }
        } else if (format == ModelFormat.VOSK) {
            // D11 marker discipline: a Vosk tree must never carry a sherpa
            // marker — a stale one would misroute the engine at load time.
            val freshTopDir = ArchiveTools.findModelTopDir(tempUnzipLocation)
            if (freshTopDir != null) ArchiveTools.deleteEngineMarker(freshTopDir)
        }

        val existedBefore = unzipFinalDestination.exists()
        val moveSuccess = try {
            if (!existedBefore) {
                unzipFinalDestination.parentFile?.mkdirs()
                if (!tempUnzipLocation.renameTo(unzipFinalDestination)) {
                    // Cross-volume rename: copy-then-delete fallback (D4),
                    // then drop the source so no half-state lingers.
                    copyRecursively(tempUnzipLocation, unzipFinalDestination, Quota.Tracker(-1))
                    deleteRecursive(tempUnzipLocation, deleteStartingFolder = true)
                }
                true
            } else {
                // Locale folder already holds a model (e.g. tiny + base Whisper):
                // merge the new model folder in, refusing on name clashes.
                mergeMove(tempUnzipLocation, unzipFinalDestination)
            }
        } catch (e: IOException) {
            errorObserver?.onChanged(e.message ?: "unzip failed")
            // mergeMove/copy already rolled back best-effort; drop leftovers.
            try {
                if (!unzipFinalDestination.exists() || !existedBefore) {
                    deleteRecursive(unzipFinalDestination, deleteStartingFolder = false)
                }
            } catch (_: Exception) {
            }
            deleteRecursive(tempUnzipLocation)
            return false
        }
        if (BuildConfig.DEBUG) {
            AppLog.d(TAG, "install done format=$format merged=$existedBefore ok=$moveSuccess")
        }
        if (!moveSuccess) {
            errorObserver?.onChanged("Model exists")
            deleteRecursive(tempUnzipLocation)
            return false
        }
        return true
    }

    /**
     * Move every child of [source] into [destination].
     *
     * Returns false (leaving [source] behind for cleanup) if a child with the
     * same name exists. D4: every target is containment-checked; if renameTo
     * fails mid-merge the copy-then-delete fallback runs, and any failure
     * after that rolls back the already-moved children best-effort and
     * THROWS — never a silent half-state.
     */
    @Throws(IOException::class)
    private fun mergeMove(source: File, destination: File): Boolean {
        val children = source.listFiles() ?: return false
        for (child in children.sortedBy { it.name }) {
            val name = child.name
            if (name.isEmpty() || name == "." || name == ".." || '\u0000' in name) {
                throw IOException("Unsafe model entry")
            }
            val target = File(destination, name)
            Sanitize.requireWithin(destination, target, "model entry")
            if (target.exists()) return false
        }
        val moved = mutableListOf<Pair<File, File>>()
        try {
            for (child in children.sortedBy { it.name }) {
                val target = File(destination, child.name)
                if (!child.renameTo(target)) {
                    copyRecursively(child, target, Quota.Tracker(-1))
                    deleteRecursive(child)
                }
                moved.add(child to target)
            }
        } catch (e: IOException) {
            // Best-effort rollback: hand back what moved, delete partial copies.
            for ((src, dst) in moved.asReversed()) {
                try {
                    if (dst.exists() && !src.exists()) {
                        if (!dst.renameTo(src)) deleteRecursive(dst)
                    } else if (dst.exists()) {
                        deleteRecursive(dst)
                    }
                } catch (_: Exception) {
                }
            }
            throw IOException("Model install failed halfway; rolled back", e)
        }
        deleteRecursive(source, deleteStartingFolder = true)
        return true
    }

    /** Quota-accounted recursive copy used by the D4 rename fallbacks. */
    @Throws(IOException::class)
    private fun copyRecursively(source: File, dest: File, tracker: Quota.Tracker) {
        Sanitize.requireWithin(dest.parentFile ?: throw IOException("Bad copy target"), dest, "copy target")
        if (source.isDirectory) {
            dest.mkdirs()
            val kids = source.listFiles() ?: throw IOException("Cannot list model files")
            for (kid in kids.sortedBy { it.name }) {
                copyRecursively(kid, File(dest, kid.name), tracker)
            }
            return
        }
        tracker.onEntry(source.length())
        try {
            FileInputStream(source).use { ins ->
                FileOutputStream(dest).use { out ->
                    val b = ByteArray(32 * 1024)
                    var n: Int
                    while (ins.read(b, 0, b.size).also { n = it } >= 0) {
                        tracker.onBytes(n.toLong())
                        out.write(b, 0, n)
                    }
                    out.flush()
                }
            }
        } catch (e: IOException) {
            dest.delete()
            throw e
        }
    }

    @Throws(IOException::class)
    private fun unzipEntry(
        zipfile: ZipFile,
        entry: ZipEntry,
        outputDir: String,
        tracker: Quota.Tracker,
        isCancelled: () -> Boolean
    ) {
        // Zip-slip guard (mirrors the tar path): reject entries that would
        // escape the destination via ../ or absolute paths.
        val destCanon = File(outputDir).canonicalPath + File.separator
        val outputFile = File(outputDir, entry.name)
        if (!outputFile.canonicalPath.startsWith(destCanon)) {
            throw IOException("Blocked path-traversal entry")
        }
        if (entry.isDirectory) {
            createDir(outputFile)
            return
        }
        val parent = outputFile.parentFile ?: throw IOException("Blocked path-traversal entry")
        if (!parent.exists()) {
            createDir(parent)
        }
        val zin = try {
            zipfile.getInputStream(entry)
        } catch (e: IOException) {
            throw e
        } ?: throw IOException("Cannot read archive entry")
        zin.use {
            BufferedInputStream(it).use { inputStream ->
                BufferedOutputStream(
                    FileOutputStream(outputFile)
                ).use { outputStream ->
                    val b = ByteArray(32 * 1024)
                    var n: Int
                    while (inputStream.read(b, 0, b.size).also { n = it } >= 0) {
                        if (isCancelled()) throw IOException("Canceled")
                        // During-write quota (D3): bounded before landing.
                        tracker.onBytes(n.toLong())
                        outputStream.write(b, 0, n)
                    }
                }
            }
        }
    }

    /** D9: directory creation failures are IOException, never RuntimeException. */
    @Throws(IOException::class)
    private fun createDir(dir: File) {
        if (dir.exists()) {
            return
        }
        if (!dir.mkdirs()) {
            throw IOException("Cannot create directory")
        }
    }
}
