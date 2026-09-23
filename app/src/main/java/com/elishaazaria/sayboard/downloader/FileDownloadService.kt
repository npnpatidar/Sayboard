package com.elishaazaria.sayboard.downloader

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Observer
import com.elishaazaria.sayboard.BuildConfig
import com.elishaazaria.sayboard.Constants
import com.elishaazaria.sayboard.Constants.getTemporaryDownloadLocation
import com.elishaazaria.sayboard.Constants.getTemporaryUnzipLocation
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.Tools
import com.elishaazaria.sayboard.data.ModelLink
import com.elishaazaria.sayboard.downloader.messages.*
import com.elishaazaria.sayboard.sayboardPreferenceModel
import com.elishaazaria.sayboard.utils.AppLog
import java.net.HttpURLConnection
import java.security.MessageDigest
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// Note on the EventBus surface below (D13/B14): this bus is in-process only.
// Its events carry no auth — CancelPending/CancelCurrent are gated on matching
// the current job / queued entry (exact url+filename), so a forged message can
// at most cancel a download the catalog UI already exposes, never authorize I/O.
class FileDownloadService : Service() {
    private val prefs by sayboardPreferenceModel()
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var notificationBuilder: NotificationCompat.Builder

    // D8: concurrent queue + volatile job state; Status posts a copy, never
    // the live queue.
    private val queuedModels: Queue<ModelInfo> = ConcurrentLinkedQueue()
    @Volatile private var currentModel: ModelInfo? = null
    @Volatile private var currentState = State.NONE
    @Volatile private var downloadProgress = 0f
    @Volatile private var unzipProgress = 0f
    @Volatile private var interrupt = false

    /** ETag/Last-Modified of the download in flight, for update checks. */
    @Volatile private var lastValidator: String? = null

    /** D5: latched by setError/setFailed; FINISHED/UNZIP_FINISHED never post after. */
    @Volatile private var jobErrored = false

    /** D7: atomic I/O byte counter (progress + diagnostics, no PII). */
    private val ioBytes = AtomicLong(0)

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        EventBus.getDefault().register(this)
        cleanStalePartials()
        notificationManager = NotificationManagerCompat.from(this)
        notificationBuilder = NotificationCompat.Builder(this, Constants.DOWNLOADER_CHANNEL_ID)
        notificationBuilder.setContentTitle(getString(R.string.notification_download_title))
            .setContentText(getString(R.string.notification_download_content_unknown))
            .setSmallIcon(R.drawable.ic_notification).priority = NotificationCompat.PRIORITY_LOW
        notificationBuilder.setProgress(0, 0, true)
        notificationBuilder.foregroundServiceBehavior =
            NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
        // D14: user-visible Cancel. Label + icon both reuse existing resources
        // (no new strings); the PendingIntent is immutable (D15-compatible).
        val cancelIntent = Intent(this, FileDownloadService::class.java)
            .putExtra(FileDownloader.ACTION, ACTION_CANCEL)
        val cancelPending = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notificationBuilder.addAction(
            R.drawable.ic_notification, getString(R.string.button_cancel), cancelPending
        )
    }

    // D9: nullable Intent override is legal; null → drop without any work.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        // D14: every branch is validated BEFORE startForeground. Unknown
        // actions and invalid payloads return without ever starting FGS.
        when (safeIntent.getStringExtra(FileDownloader.ACTION)) {
            FileDownloader.ACTION_DOWNLOAD -> {
                // getInfoForIntent already edge-validates url/filename/locale.
                val modelInfo = FileDownloader.getInfoForIntent(safeIntent)
                    ?: return START_NOT_STICKY
                // D16 service-layer gate (UI gate alone is bypassable via
                // direct intents): refuse network starts while offline mode
                // is on, before any FGS/queue/I/O. Local SAF import actions
                // below are unaffected — they need no network.
                if (isOfflineMode()) {
                    refuseOffline(modelInfo)
                    return START_NOT_STICKY
                }
                startForegroundChecked()
                if (safeIntent.getBooleanExtra(FileDownloader.DOWNLOAD_FRESH, false)) {
                    // Update re-download: drop any partial and the old
                    // integrity record so a changed upstream file installs.
                    val stale = getTemporaryDownloadLocation(this, modelInfo.filename)
                    if (stale.exists()) stale.delete()
                    Sanitize.metaFileFor(stale).delete()
                    val hashes = prefs.downloadHashes.get() - modelInfo.url
                    prefs.downloadHashes.set(hashes)
                    debugLog("fresh download, cleared record")
                }
                queuedModels.add(modelInfo)
                sendEnqueued(modelInfo)
                executor.execute { runGuarded { main() } }
            }

            FileDownloader.ACTION_UNZIP -> {
                val uri = pickUriExtra(safeIntent, FileDownloader.UNZIP_URI)
                if (uri == null) {
                    postOrphanError("No file picked")
                    return START_NOT_STICKY
                }
                startForegroundChecked()
                executor.execute { runGuarded { unzipUri(uri) } }
            }

            FileDownloader.ACTION_IMPORT_FILES -> {
                val uris = pickUriListExtra(safeIntent, FileDownloader.IMPORT_URIS)
                if (uris.isEmpty() || uris.size > Quota.MAX_IMPORT_URIS) {
                    postOrphanError(
                        if (uris.isEmpty()) "No files picked"
                        else "Too many files (max ${Quota.MAX_IMPORT_URIS})"
                    )
                    return START_NOT_STICKY
                }
                startForegroundChecked()
                val snapshot = uris.toList()
                executor.execute { runGuarded { importFiles(snapshot) } }
            }

            FileDownloader.ACTION_IMPORT_FOLDER -> {
                val uriString = safeIntent.getStringExtra(FileDownloader.IMPORT_FOLDER_URI)
                if (uriString.isNullOrEmpty()) {
                    postOrphanError("No folder picked")
                    return START_NOT_STICKY
                }
                startForegroundChecked()
                executor.execute { runGuarded { importFolder(uriString) } }
            }

            ACTION_CANCEL -> {
                // No FGS needed: just flag the worker; the CANCELED terminal
                // posts from the job thread.
                interrupt = true
                return START_NOT_STICKY
            }

            else -> return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun startForegroundChecked() {
        if (foregroundStarted) return
        foregroundStarted = true
        try {
            startForeground(notificationId, notificationBuilder.build())
        } catch (e: Exception) {
            AppLog.e(TAG, "foreground start failed", e)
        }
    }

    /** Error posted without a job and without FGS (D14 null-payload path). */
    private fun postOrphanError(message: String) {
        val orphan = ModelInfo("invalid", "invalid")
        EventBus.getDefault().post(DownloadState(orphan, State.ERROR))
        EventBus.getDefault().post(DownloadError(orphan, message))
    }

    /**
     * D16: offline pref read defensively — any read failure means default-off
     * (fail-open toward the pre-existing behavior, never a crash loop).
     */
    private fun isOfflineMode(): Boolean {
        return try {
            prefs.logicOfflineMode.get()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * D16: offline refusal for a network download start. Terminal and
     * deterministic, so it posts FAILED (D5) rather than transient ERROR;
     * no FGS is started and nothing is queued. Like postOrphanError it only
     * posts bus events — it never touches the live job state, so a refusal
     * racing an in-flight worker cannot corrupt it. Reuses the pre-existing
     * offline-blocked string (no new R.string keys).
     */
    private fun refuseOffline(modelInfo: ModelInfo) {
        val message = try {
            getString(R.string.logic_offline_blocked)
        } catch (_: Exception) {
            "Offline mode is on"
        }
        AppLog.e(TAG, "download refused (offline mode)")
        EventBus.getDefault().post(DownloadState(modelInfo, State.FAILED))
        EventBus.getDefault().post(DownloadError(modelInfo, message))
    }

    /** D9: a worker must never let a Throwable escape; it becomes setError. */
    private fun runGuarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            try {
                if (currentModel == null) currentModel = ModelInfo("unknown", "unknown")
                if (!jobErrored) setError(t.message ?: t.javaClass.simpleName)
            } catch (_: Exception) {
            } finally {
                mainEnd()
            }
        }
    }

    private fun pickUriExtra(intent: Intent, key: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(key, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(key)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun pickUriListExtra(intent: Intent, key: String): List<Uri> {
        return try {
            val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(key, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(key)
            }
            list ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun unzipUri(uri: Uri) {
        currentModel = ModelInfo("import", "ImportedFile.zip")
        jobErrored = false
        interrupt = false
        ioBytes.set(0)
        try {
            val filename = "ImportedFile.zip"
            val file = getTemporaryDownloadLocation(this, filename)
            file.parentFile?.mkdirs()
            copySafToFile(uri, file, Quota.Tracker(-1))
            debugLog("import copied bytes=${file.length()}")
            unzipProgress = 0f
            setState(State.NONE)
            unzipFile(file)
            if (interrupt) {
                interrupted(file)
                return
            }
            file.delete()
            if (!jobErrored) setState(State.FINISHED)
        } catch (t: Throwable) {
            failOrCancel(t)
        } finally {
            mainEnd()
        }
    }

    /**
     * Import an extracted model folder picked via the directory picker:
     * recursive copy, then the same validate + install as archives.
     */
    private fun importFolder(uriString: String?) {
        // Placeholder so progress/state posts have a model before validation
        currentModel = ModelInfo("import", "folder", Constants.UndefinedLocale)
        jobErrored = false
        interrupt = false
        ioBytes.set(0)
        try {
            setState(State.UNZIP_STARTED)
            if (uriString.isNullOrEmpty()) throw IOException("No folder picked")
            val uri = try {
                Uri.parse(uriString)
            } catch (e: Exception) {
                throw IOException("Bad folder uri", e)
            }
            val root = try {
                DocumentFile.fromTreeUri(this, uri)
            } catch (e: SecurityException) {
                throw IOException("Picked folder is not readable", e)
            } catch (e: NullPointerException) {
                throw IOException("Picked folder is not readable", e)
            }
            if (root == null) throw IOException("Picked folder is not readable")
            try {
                if (!root.isDirectory) throw IOException("Picked folder is not readable")
            } catch (e: IOException) {
                throw e
            } catch (e: SecurityException) {
                throw IOException("Picked folder is not readable", e)
            }
            val stagingRoot = File(
                getTemporaryUnzipLocation(this).parentFile ?: cacheDir, "ImportFiles"
            )
            if (stagingRoot.exists()) Tools.deleteRecursive(stagingRoot)
            stagingRoot.mkdirs()
            Quota.checkFreeSpace(stagingRoot, 0)
            val treeName = try {
                root.name
            } catch (_: Exception) {
                null
            }
            val safeName = Sanitize.flattenName(treeName)
            val modelDir = File(stagingRoot, Sanitize.uniquify(stagingRoot, "imported-$safeName"))
            Sanitize.requireWithin(stagingRoot, modelDir, "import folder")
            modelDir.mkdirs()
            val tracker = Quota.Tracker(-1)
            copyTree(root, modelDir, tracker, 0)
            debugLog("import folder copied files=${tracker.entries} bytes=${tracker.bytes}")
            setUnzipProgress(0.5f)
            val scan = ArchiveTools.scan(modelDir)
            val format = scan.format()
            if (format == ModelFormat.UNKNOWN) {
                Tools.deleteRecursive(stagingRoot)
                throw IOException("Folder is not a recognized model (${scan.summary()})")
            }
            // Vosk folders carry their locale in the name; sherpa ones decode any language.
            val locale = if (format == ModelFormat.VOSK) {
                ArchiveTools.detectLocaleFromName(safeName) ?: Constants.UndefinedLocale
            } else {
                Constants.UndefinedLocale
            }
            currentModel = ModelInfo("import", modelDir.name, locale)
            val ok = ZipTools.finishInstall(
                stagingRoot, format, locale, applicationContext,
                errorObserver = { setFailed(it) }
            )
            if (!ok) throw IOException("Install failed")
            setUnzipProgress(1f)
            if (!jobErrored) setState(State.UNZIP_FINISHED)
            if (!jobErrored) setState(State.FINISHED)
            try {
                contentResolver?.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        } catch (t: Throwable) {
            failOrCancel(t)
        } finally {
            mainEnd()
        }
    }

    @Throws(IOException::class)
    private fun copyTree(
        source: DocumentFile,
        destDir: File,
        tracker: Quota.Tracker,
        depth: Int
    ) {
        if (depth > 8) throw IOException("Folder is nested too deep")
        val children = try {
            source.listFiles().toList()
        } catch (e: SecurityException) {
            throw IOException("Cannot list folder", e)
        } catch (e: NullPointerException) {
            throw IOException("Cannot list folder", e)
        } catch (e: Exception) {
            throw IOException("Cannot list folder", e)
        }
        if (children.size > Quota.MAX_SCAN_BREADTH) throw IOException("Folder is too wide")
        for (child in children.sortedBy { childName(it) }) {
            if (interrupt) throw IOException("Canceled")
            val rawName = childName(child) ?: continue
            val target = File(destDir, Sanitize.uniquify(destDir, Sanitize.flattenName(rawName)))
            Sanitize.requireWithin(destDir, target, "folder entry")
            val isDir = try {
                child.isDirectory
            } catch (_: Exception) {
                false
            }
            val isFile = if (!isDir) {
                try {
                    child.isFile
                } catch (_: Exception) {
                    false
                }
            } else false
            if (isDir) {
                target.mkdirs()
                copyTree(child, target, tracker, depth + 1)
            } else if (isFile) {
                tracker.onEntry()
                copySafToFile(child.uri, target, tracker)
            }
        }
    }

    private fun childName(doc: DocumentFile): String? {
        return try {
            doc.name
        } catch (_: Exception) {
            null
        }
    }

    /** D4/D10: flattened SAF display name; never null/blank/traversal. */
    private fun contentName(uri: Uri?): String {
        var raw: String? = null
        if (uri != null) {
            try {
                contentResolver?.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) raw = cursor.getString(idx)
                }
            } catch (_: NullPointerException) {
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
            if (raw.isNullOrEmpty()) {
                try {
                    raw = uri.lastPathSegment?.substringAfterLast('/')
                } catch (_: Exception) {
                }
            }
        }
        return Sanitize.flattenName(raw)
    }

    /**
     * Capped SAF copy shared by unzip/import paths (D3/D9/D10): null-stream
     * guards (no `!!` on external inputs), during-write quota accounting,
     * NPE/SecurityException mapped to IOException, partial deleted on failure.
     */
    @Throws(IOException::class)
    private fun copySafToFile(uri: Uri, dest: File, tracker: Quota.Tracker) {
        val resolver = contentResolver ?: throw IOException("Cannot read picked file")
        try {
            val stream = try {
                resolver.openInputStream(uri)
            } catch (e: SecurityException) {
                throw IOException("Cannot read picked file", e)
            } catch (e: NullPointerException) {
                throw IOException("Cannot read picked file", e)
            } ?: throw IOException("Cannot read picked file")
            stream.use { ins ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (interrupt) throw IOException("Canceled")
                        val n = ins.read(buf)
                        if (n < 0) break
                        tracker.onBytes(n.toLong())
                        out.write(buf, 0, n)
                        ioBytes.addAndGet(n.toLong())
                    }
                    out.flush()
                }
            }
        } catch (e: IOException) {
            dest.delete()
            throw e
        }
    }

    /**
     * Import loose model files (e.g. encoder.onnx + decoder.onnx + tokens.txt
     * downloaded individually from Hugging Face) as one sherpa-onnx model.
     * Same caps/quotas as the folder path (D10 parity).
     */
    private fun importFiles(uris: List<Uri>) {
        // Placeholder so progress/state posts have a model before validation
        currentModel = ModelInfo("import", "files", Constants.UndefinedLocale)
        jobErrored = false
        interrupt = false
        ioBytes.set(0)
        try {
            if (uris.isEmpty()) throw IOException("No files picked")
            if (uris.size > Quota.MAX_IMPORT_URIS) {
                throw IOException("Too many files (max ${Quota.MAX_IMPORT_URIS})")
            }
            setState(State.UNZIP_STARTED)
            val stagingRoot = File(
                getTemporaryUnzipLocation(this).parentFile ?: cacheDir, "ImportFiles"
            )
            if (stagingRoot.exists()) Tools.deleteRecursive(stagingRoot)
            stagingRoot.mkdirs()
            Quota.checkFreeSpace(stagingRoot, 0)
            val firstName = contentName(uris.firstOrNull())
            val modelDir = File(
                stagingRoot,
                Sanitize.uniquify(stagingRoot, "imported-$firstName")
            )
            Sanitize.requireWithin(stagingRoot, modelDir, "import folder")
            modelDir.mkdirs()
            val tracker = Quota.Tracker(-1)
            uris.forEach { uri ->
                if (interrupt) throw IOException("Canceled")
                val target = File(modelDir, Sanitize.uniquify(modelDir, contentName(uri)))
                Sanitize.requireWithin(modelDir, target, "import file")
                tracker.onEntry()
                copySafToFile(uri, target, tracker)
            }
            setUnzipProgress(0.5f)
            val scan = ArchiveTools.scan(modelDir)
            val format = scan.format()
            if (format != ModelFormat.WHISPER && format != ModelFormat.PARAKEET) {
                Tools.deleteRecursive(stagingRoot)
                throw IOException("Files are not a Whisper/Parakeet model (need encoder + decoder + tokens.txt)")
            }
            currentModel = ModelInfo("import", modelDir.name, Constants.UndefinedLocale)
            val ok = ZipTools.finishInstall(
                stagingRoot, format, Constants.UndefinedLocale, applicationContext,
                errorObserver = { setFailed(it) }
            )
            if (!ok) throw IOException("Install failed")
            setUnzipProgress(1f)
            if (!jobErrored) setState(State.UNZIP_FINISHED)
            if (!jobErrored) setState(State.FINISHED)
        } catch (t: Throwable) {
            failOrCancel(t)
        } finally {
            mainEnd()
        }
    }

    /**
     * Drop partial downloads abandoned more than 7 days ago so cancelled
     * jobs kept for resume don't accumulate forever. Same 7d/size bound as
     * the resume gate (D6); filenames are service-owned, no PII logged.
     */
    private fun cleanStalePartials() {
        try {
            val dir = getTemporaryDownloadLocation(this, "x").parentFile ?: return
            val cutoff = System.currentTimeMillis() - Quota.PARTIAL_MAX_AGE_MS
            var cleaned = 0L
            var count = 0
            for (f in dir.listFiles() ?: return) {
                if (interrupt) return
                if (!f.isFile || f.lastModified() >= cutoff) continue
                // Only reap our own partials + sidecars, never unknown files.
                if (!f.name.endsWith(".meta") && Sanitize.sanitizeFilenameStrict(f.name) == null) {
                    continue
                }
                cleaned += f.length()
                count++
                f.delete()
                if (count > Quota.MAX_ENTRIES) break
            }
            if (count > 0) debugLog("cleaned stale partials count=$count bytes=$cleaned")
        } catch (e: Exception) {
            AppLog.e(TAG, "stale partial cleanup failed", e)
        }
    }

    private fun main() {
        val job = queuedModels.poll() ?: return
        currentModel = job
        jobErrored = false
        interrupt = false
        ioBytes.set(0)
        // D16: TOCTOU re-check — offline mode may have been enabled after
        // the intent gate. Fail the job terminally instead of fetching.
        if (isOfflineMode()) {
            try {
                refuseOffline(job)
            } catch (_: Exception) {
            } finally {
                mainEnd()
            }
            return
        }
        try {
            downloadProgress = 0f
            unzipProgress = 0f
            setState(State.NONE)
            // D1/D4: the filename was allow-list validated at the intent edge;
            // re-check defensively before it reaches File().
            if (Sanitize.sanitizeFilenameStrict(job.filename) != job.filename) {
                throw IOException("Unsafe download filename")
            }
            val downloadLocation = getTemporaryDownloadLocation(
                applicationContext, job.filename
            )
            downloadLocation.parentFile?.mkdirs()
            downloadFile(downloadLocation)
            if (interrupt) {
                interrupted(downloadLocation)
                return
            }
            verifyDownload(downloadLocation, job.url)
            unzipFile(downloadLocation)
            if (interrupt) {
                interrupted(downloadLocation)
                return
            }
            downloadLocation.delete()
            Sanitize.metaFileFor(downloadLocation).delete()
            // D5: FINISHED is terminal-success only; error paths above threw
            // and latched jobErrored via setFailed/failOrCancel.
            if (!jobErrored) setState(State.FINISHED)
        } catch (t: Throwable) {
            failOrCancel(t)
        } finally {
            mainEnd()
        }
    }

    private fun interrupted(downloadLocation: File?) {
        // The partial download is deliberately kept: the next attempt
        // resumes it via Range (bound to url-hash + validators, D6). Only
        // temp extraction state is cleaned.
        val unzipFolder = getTemporaryUnzipLocation(this)
        if (unzipFolder.exists()) {
            Tools.deleteRecursive(unzipFolder)
        }
        val info = currentModel ?: return
        EventBus.getDefault().post(CancelFinished(info))
        updateNotification()
        interrupt = false
    }

    private fun mainEnd() {
        downloadProgress = 0f
        unzipProgress = 0f
        currentState = State.NONE
        currentModel = null
        // D14: release FGS and stop when the queue drains.
        if (queuedModels.isEmpty()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Exception) {
            }
            foregroundStarted = false
            stopSelf()
        }
    }

    /**
     * Hardened GET (D1/D6/D7/D3): allow-listed https with manual redirects,
     * resume bound to url-hash + meta validators, 10s/15s timeouts,
     * getContentLengthLong, response-code switch (200/206/416/404), `use`
     * streams + disconnect-in-finally, during-write quota, atomic counters.
     */
    @Throws(IOException::class)
    private fun downloadFile(downloadLocation: File) {
        setState(State.DOWNLOAD_STARTED)
        val job = currentModel ?: throw IOException("No current download")
        val url = Sanitize.sanitizeDownloadUrl(job.url)
        // D1: pin the download filename to the catalog entry for known URLs.
        val catalog = ModelLink.entries.firstOrNull { it.link == job.url }
        if (catalog != null && job.filename != catalog.filename) {
            throw IOException("Filename does not match catalog")
        }
        // Fail-closed catalog size pre-check (D2): never fetch a body that
        // already contradicts the published size.
        val catalogBytes = catalog?.expectedBytes

        // D6: resume only a partial bound to THIS url (hash) with a fresh meta.
        val metaFile = Sanitize.metaFileFor(downloadLocation)
        var existing = 0L
        var resumeValidator: String? = null
        if (downloadLocation.exists()) {
            val meta = Sanitize.PartialMeta.loadFrom(metaFile)
            val fresh = meta != null &&
                System.currentTimeMillis() - meta.createdAt < Quota.PARTIAL_MAX_AGE_MS
            val bound = meta != null && meta.urlHash == Sanitize.partialKey(job.url)
            val sizeOk = downloadLocation.length() in 1..Quota.MAX_PARTIAL_BYTES
            if (bound && fresh && sizeOk) {
                existing = downloadLocation.length()
                resumeValidator = meta.validator
                debugLog("resume candidate bytes=$existing")
            } else {
                downloadLocation.delete()
                metaFile.delete()
                existing = 0L
            }
        }
        downloadLocation.parentFile?.mkdirs()
        Quota.checkFreeSpace(downloadLocation.parentFile ?: cacheDir, existing)

        var conn = Sanitize.openAllowedConnection(url) { c ->
            if (existing > 0) c.setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_NOT_FOUND ->
                    throw IOException("Model not found (404)")
                416 -> {
                    // D6: restart-from-scratch on 416.
                    conn.disconnect()
                    downloadLocation.delete()
                    metaFile.delete()
                    existing = 0L
                    resumeValidator = null
                    conn = Sanitize.openAllowedConnection(url, null)
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("Unexpected HTTP ${conn.responseCode}")
                    }
                }
                HttpURLConnection.HTTP_OK -> {
                    if (existing > 0) {
                        // Server ignored Range: restart from scratch.
                        debugLog("server ignored Range, restarting")
                        downloadLocation.delete()
                        metaFile.delete()
                        existing = 0L
                        resumeValidator = null
                    }
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    if (existing <= 0) throw IOException("Unexpected 206 without partial")
                    // D6: verify Accept-Ranges + validator continuity.
                    val accept = conn.getHeaderField("Accept-Ranges")
                    if (accept != null && !accept.contains("bytes", ignoreCase = true)) {
                        throw IOException("Server does not support resume")
                    }
                    val current = conn.getHeaderField("ETag")
                        ?: conn.getHeaderField("Last-Modified")
                    if (resumeValidator != null && current != null &&
                        resumeValidator != current
                    ) {
                        conn.disconnect()
                        downloadLocation.delete()
                        metaFile.delete()
                        existing = 0L
                        resumeValidator = null
                        conn = Sanitize.openAllowedConnection(url, null)
                        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                            throw IOException("Unexpected HTTP ${conn.responseCode}")
                        }
                    }
                }
                in 300..399 -> throw IOException("Unhandled redirect (${code})")
                else -> throw IOException("Unexpected HTTP $code")
            }
            val responseValidator = conn.getHeaderField("ETag")
                ?: conn.getHeaderField("Last-Modified")
            lastValidator = responseValidator
            // D7: long length, never int (contentLengthLong is API 24+).
            val bodyLen = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) conn.contentLengthLong
                else conn.contentLength.toLong()
            } catch (_: Exception) {
                -1L
            }
            val totalExpected = if (bodyLen > 0) {
                if (existing > 0) existing + bodyLen else bodyLen
            } else {
                -1L
            }
            if (catalogBytes != null && totalExpected > 0 && catalogBytes != totalExpected) {
                downloadLocation.delete()
                metaFile.delete()
                throw IOException("Download failed integrity check, please retry")
            }
            if (totalExpected > 0) {
                Quota.checkFreeSpace(downloadLocation.parentFile ?: cacheDir, totalExpected)
            }
            setDownloadProgress(
                if (existing > 0 && totalExpected > 0) {
                    (existing.toFloat() / totalExpected).coerceIn(0f, 1f)
                } else 0f
            )
            // Persist the binding BEFORE the body lands, so a killed job can
            // only resume when url + validators still match.
            Sanitize.PartialMeta(
                urlHash = Sanitize.partialKey(job.url),
                validator = responseValidator,
                total = totalExpected,
                createdAt = System.currentTimeMillis()
            ).saveTo(metaFile)
            // Read from the SAME connection that carries Range: opening a
            // second stream drops Range and corrupts resumed downloads.
            val tracker = Quota.Tracker(totalExpected)
            var read: Long = existing
            conn.getInputStream().use { rawIn ->
                BufferedInputStream(rawIn).use { input ->
                    FileOutputStream(downloadLocation, existing > 0).use { output ->
                        val data = ByteArray(32 * 1024)
                        while (true) {
                            if (interrupt) break
                            val count = input.read(data)
                            if (count < 0) break
                            tracker.onBytes(count.toLong())
                            if (catalogBytes != null && read + count > catalogBytes) {
                                throw IOException("Download failed integrity check, please retry")
                            }
                            output.write(data, 0, count)
                            read += count
                            ioBytes.addAndGet(count.toLong())
                            if (totalExpected > 0) {
                                setDownloadProgress(
                                    (read.toFloat() / totalExpected).coerceIn(0f, 1f)
                                )
                            }
                        }
                        output.flush()
                    }
                }
            }
            debugLog("download done bytes=${downloadLocation.length()}")
            if (!interrupt) {
                setDownloadProgress(1f)
                setState(State.DOWNLOAD_FINISHED)
            }
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** SHA-256 of a downloaded archive, hex. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { ins ->
            val buf = ByteArray(256 * 1024)
            var n: Int
            while (ins.read(buf).also { n = it } >= 0) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Integrity gate after download (D2 enforcement). Fail-closed:
     * - catalog expectedSha256/expectedBytes, when non-null, are enforced and
     *   mismatch deletes the partial + errors;
     * - otherwise the TOFU downloadHashes record still catches truncation and
     *   changed content (record stored on first success).
     * Null catalog hashes mean TOFU pending upstream hash publication (see
     * ModelLink docs); nulls are left as-is, never invented here.
     * Validators (ETag/Last-Modified) stay informational only.
     */
    @Throws(IOException::class)
    private fun verifyDownload(file: File, url: String) {
        val actual = file.length()
        val hash = sha256(file)
        val catalog = ModelLink.entries.firstOrNull { it.link == url }
        val expHash = catalog?.expectedSha256
        val expBytes = catalog?.expectedBytes
        if ((expHash != null && !expHash.equals(hash, ignoreCase = true)) ||
            (expBytes != null && expBytes != actual)
        ) {
            debugLog("catalog integrity mismatch bytes=$actual")
            file.delete()
            Sanitize.metaFileFor(file).delete()
            prefs.downloadHashes.set(prefs.downloadHashes.get() - url)
            throw IOException("Download failed integrity check, please retry")
        }
        val recorded = prefs.downloadHashes.get()[url]
        if (recorded != null) {
            // Limit 3: validators (dates/ETags) may contain colons.
            val parts = recorded.split(":", limit = 3)
            val recLen = parts.getOrNull(0)?.toLongOrNull()
            val recHash = parts.getOrNull(1)
            if ((recLen != null && recLen != actual) || (recHash != null && recHash != hash)) {
                debugLog("record integrity mismatch bytes=$actual")
                file.delete()
                Sanitize.metaFileFor(file).delete()
                prefs.downloadHashes.set(prefs.downloadHashes.get() - url)
                throw IOException("Download failed integrity check, please retry")
            }
        }
        val validator = lastValidator
        val record = if (validator.isNullOrEmpty()) "$actual:$hash" else "$actual:$hash:$validator"
        prefs.downloadHashes.set(prefs.downloadHashes.get() + (url to record))
        debugLog("verified bytes=$actual")
    }

    /**
     * D5: every failure path here throws AFTER setFailed (or throws directly,
     * letting the caller convert via failOrCancel → FAILED). UNZIP_FINISHED
     * posts on success only — never after an error.
     */
    @Throws(IOException::class)
    private fun unzipFile(downloadLocation: File) {
        setState(State.UNZIP_STARTED)
        val job = currentModel ?: throw IOException("No current model")
        val progress: Observer<Double> = { d: Double -> setUnzipProgress(d.toFloat()) }
        val isTbz2 = ArchiveTools.isTarBz2(downloadLocation)
        val isZip = ArchiveTools.isZip(downloadLocation)
        debugLog("unzip start bytes=${downloadLocation.length()} tarbz2=$isTbz2 zip=$isZip")
        if (isTbz2) {
            // sherpa-onnx releases ship .tar.bz2 archives
            val tempUnzipLocation = getTemporaryUnzipLocation(this)
            if (tempUnzipLocation.exists()) {
                Tools.deleteRecursive(tempUnzipLocation)
            }
            tempUnzipLocation.parentFile?.mkdirs()
            ArchiveTools.extractTarBz2(
                downloadLocation, tempUnzipLocation, progress,
                isCancelled = { interrupt }
            )
            val scan = ArchiveTools.scan(tempUnzipLocation)
            val format = scan.format()
            if (format == ModelFormat.UNKNOWN) {
                Tools.deleteRecursive(tempUnzipLocation)
                val msg = "Not a recognized model (${scan.summary()})"
                setFailed(msg)
                throw IOException(msg)
            }
            val ok = ZipTools.finishInstall(
                tempUnzipLocation, format, job.locale,
                applicationContext,
                errorObserver = { setFailed(it) }
            )
            if (!ok) throw IOException("Install failed")
        } else if (isZip) {
            val ok = ZipTools.unzip(
                downloadLocation, job.locale, applicationContext,
                errorObserver = { setFailed(it) },
                progressObserver = progress,
                isCancelled = { interrupt }
            )
            if (!ok) throw IOException("Install failed")
        } else {
            val msg = "Not a recognized archive"
            setFailed(msg)
            throw IOException(msg)
        }
        if (interrupt) return
        setUnzipProgress(1f)
        // D5 guard: success-only terminal.
        if (!jobErrored) setState(State.UNZIP_FINISHED)
    }

    private var lastDownloadProgress = 0f
    private var lastUnzipProgress = 0f
    private var lastState: State = State.NONE
    private var lastUpdateTime: Long = 0
    private fun updateNotification() {
        if (lastDownloadProgress == downloadProgress && lastUnzipProgress == unzipProgress && lastState == currentState) // nothing changed
            return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < minUpdateTime) {
            if (lastState == currentState) { // it's a progress update
                if (!(downloadProgress == 1f && unzipProgress == 0f) && unzipProgress != 1f) { // it's not the last progress update
                    lastUpdateTime = currentTime
                    return
                }
            }
        }
        lastUpdateTime = currentTime
        when (currentState) {
            State.NONE -> notificationBuilder.setContentText(getString(R.string.notification_download_content_unknown))
                .setProgress(0, 0, true)

            State.DOWNLOAD_STARTED, State.DOWNLOAD_FINISHED -> notificationBuilder.setContentText(
                getString(R.string.notification_download_content_downloading)
            ).setProgress(PROGRESS_MAX, (downloadProgress * PROGRESS_MAX).toInt(), false)

            State.UNZIP_STARTED, State.UNZIP_FINISHED -> notificationBuilder.setContentText(
                getString(R.string.notification_download_content_unzipping)
            ).setProgress(PROGRESS_MAX, (unzipProgress * PROGRESS_MAX).toInt(), false)

            State.FINISHED -> notificationBuilder.setContentText(getString(R.string.notification_download_content_finished))
                .setProgress(0, 0, false)

            State.ERROR -> notificationBuilder.setContentText(getString(R.string.notification_download_content_error))
                .setProgress(0, 0, false)

            // D5: FAILED reuses the pre-existing error text (no new strings).
            State.FAILED -> notificationBuilder.setContentText(getString(R.string.notification_download_content_error))
                .setProgress(0, 0, false)

            State.CANCELED -> notificationBuilder.setContentText(getString(R.string.models_download_state_canceled))
                .setProgress(0, 0, false)

            else -> {}
        }

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(notificationId, notificationBuilder.build())
        }
        lastDownloadProgress = downloadProgress
        lastUnzipProgress = unzipProgress
        lastState = currentState
    }

    private fun setState(state: State) {
        currentState = state
        // currentModel is null between jobs; never crash the service on it.
        currentModel?.let { EventBus.getDefault().post(DownloadState(it, state)) }
        updateNotification()
    }

    private var lastPostedDownloadProgress = -1f
    private var lastPostedUnzipProgress = -1f

    private fun setDownloadProgress(progress: Float) {
        downloadProgress = progress
        // 116MB+ archives post per-KB reads; coalesce to 0.5% steps so
        // LiveData/EventBus observers aren't flooded into a hang.
        if (progress - lastPostedDownloadProgress >= 0.005f || progress >= 1f) {
            lastPostedDownloadProgress = progress
            currentModel?.let { EventBus.getDefault().post(DownloadProgress(it, downloadProgress)) }
        }
        updateNotification()
    }

    private fun setUnzipProgress(progress: Float) {
        unzipProgress = progress
        if (progress - lastPostedUnzipProgress >= 0.005f || progress >= 1f) {
            lastPostedUnzipProgress = progress
            currentModel?.let { EventBus.getDefault().post(UnzipProgress(it, unzipProgress)) }
        }
        updateNotification()
    }

    /**
     * D5 transient path: unexpected worker throwables (via [runGuarded]) post
     * ERROR. Deterministic install/download failures must use [setFailed].
     */
    private fun setError(message: String?) {
        jobErrored = true
        val info = currentModel ?: ModelInfo("unknown", "unknown").also { currentModel = it }
        // D19: hosts/hashes/counts only — never URLs, paths, or locales.
        AppLog.e(TAG, "download error")
        setState(State.ERROR)
        EventBus.getDefault().post(DownloadError(info, message ?: ""))
        updateNotification()
    }

    /**
     * D5 terminal path: deterministic install/download failures (integrity
     * mismatch, unrecognized archive/model, failed install, offline refusal)
     * post FAILED so the UI can distinguish them from transient ERROR.
     * Still latches [jobErrored], so no FINISHED posts after.
     */
    private fun setFailed(message: String?) {
        jobErrored = true
        val info = currentModel ?: ModelInfo("unknown", "unknown").also { currentModel = it }
        // D19: hosts/hashes/counts only — never URLs, paths, or locales.
        AppLog.e(TAG, "download failed")
        setState(State.FAILED)
        EventBus.getDefault().post(DownloadError(info, message ?: ""))
        updateNotification()
    }

    /** D19: debug-gated verbose; release logs stay at counts/hosts/hashes. */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) AppLog.d(TAG, message)
    }

    /**
     * D5 terminal rule: a user Cancel wins over any in-flight error — post
     * CANCELED (idempotent) and never FAILED after it. Otherwise convert the
     * install/download failure once via setFailed. Unexpected worker
     * throwables that escape to runGuarded stay transient ERROR there.
     */
    private fun failOrCancel(t: Throwable) {
        if (interrupt) {
            setState(State.CANCELED)
            interrupt = false
        } else if (!jobErrored) {
            setFailed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun sendEnqueued(modelInfo: ModelInfo) {
        EventBus.getDefault().post(DownloadState(modelInfo, State.QUEUED))
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleStatusQuery(event: StatusQuery?) {
        // D8: snapshot (copy) the queue; never leak the live one.
        EventBus.getDefault().post(
            Status(
                currentModel, LinkedList(queuedModels),
                downloadProgress, unzipProgress, currentState
            )
        )
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleCancelPending(event: CancelPending) {
        // D13: only an exactly-matching queued entry may be removed.
        if (queuedModels.remove(event.info)) {
            EventBus.getDefault().post(
                Status(
                    currentModel, LinkedList(queuedModels),
                    downloadProgress, unzipProgress, currentState
                )
            )
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleCancelCurrent(event: CancelCurrent) {
        // D13: gate on the current job (url + filename); forged cancels for
        // other models are ignored. Bus is in-process only, never auth.
        val cur = currentModel
        if (cur != null && cur.url == event.info.url && cur.filename == event.info.filename) {
            interrupt = true
            setState(State.CANCELED)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSelf()
        EventBus.getDefault().unregister(this)
    }

    companion object {
        private const val TAG = "FileDownloadService"
        private const val notificationId = 1
        private const val PROGRESS_MAX = 100

        /** Internal cancel action for the notification button (D14). */
        const val ACTION_CANCEL = "action_cancel"
        private const val minUpdateTime = (1000 / 5 // 5 updates a second;
                ).toLong()
    }
}
