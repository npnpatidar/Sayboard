package com.elishaazaria.sayboard.downloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.elishaazaria.sayboard.data.ModelLink
import com.elishaazaria.sayboard.downloader.FileDownloadService
import com.elishaazaria.sayboard.downloader.messages.ModelInfo
import java.util.*

object FileDownloader {
    const val ACTION = "action"
    const val ACTION_DOWNLOAD = "action_download"
    const val ACTION_UNZIP = "action_unzip"
    const val ACTION_IMPORT_FILES = "action_import_files"
    const val ACTION_IMPORT_FOLDER = "action_import_folder"

    const val DOWNLOAD_URL = "download_url"
    const val DOWNLOAD_FILENAME = "download_filename"
    const val DOWNLOAD_LOCALE = "download_locale"
    const val DOWNLOAD_FRESH = "download_fresh"

    const val UNZIP_URI = "unzip_uri"
    const val UNZIP_LOCALE = "unzip_locale"
    const val IMPORT_URIS = "import_uris"
    const val IMPORT_FOLDER_URI = "import_folder_uri"
    fun getInfoForIntent(intent: Intent): ModelInfo? {
        val url = intent.getStringExtra(DOWNLOAD_URL)
        val filename = intent.getStringExtra(DOWNLOAD_FILENAME)
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(DOWNLOAD_LOCALE, Locale::class.java)
        } else {
            intent.getSerializableExtra(DOWNLOAD_LOCALE) as Locale?
        }
        if (url == null || filename == null || locale == null) return null
        // D1/D4 edge gate: https + allow-listed host, strict catalog-shaped
        // filename, sane locale tag. Fail-closed: null drops the job before
        // any I/O or foreground work. Actions/extras below are frozen.
        try {
            Sanitize.sanitizeDownloadUrl(url)
        } catch (_: Exception) {
            return null
        }
        if (Sanitize.sanitizeFilenameStrict(filename) != filename) return null
        if (locale.toLanguageTag().length > 35) return null
        return ModelInfo(url, filename, locale)
    }

    fun downloadModel(model: ModelLink, context: Context, fresh: Boolean = false) {
        var context = context
        context = context.applicationContext
        val serviceIntent = Intent(context, FileDownloadService::class.java)
        serviceIntent.putExtra(ACTION, ACTION_DOWNLOAD)
        serviceIntent.putExtra(DOWNLOAD_URL, model.link)
        serviceIntent.putExtra(DOWNLOAD_FILENAME, model.filename)
        serviceIntent.putExtra(DOWNLOAD_LOCALE, model.locale)
        serviceIntent.putExtra(DOWNLOAD_FRESH, fresh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun importModel(uri: Uri, context: Context) {
        var context = context
        context = context.applicationContext
        val serviceIntent = Intent(context, FileDownloadService::class.java)
        serviceIntent.putExtra(ACTION, ACTION_UNZIP)
        serviceIntent.putExtra(UNZIP_URI, uri)
//        serviceIntent.putExtra(UNZIP_LOCALE, locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    /**
     * Import an extracted model folder (e.g. an unzipped Whisper directory)
     * picked via the directory picker.
     */
    fun importModelFolder(uri: Uri, context: Context) {
        var context = context
        context = context.applicationContext
        val serviceIntent = Intent(context, FileDownloadService::class.java)
        serviceIntent.putExtra(ACTION, ACTION_IMPORT_FOLDER)
        serviceIntent.putExtra(IMPORT_FOLDER_URI, uri.toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    /**
     * Import loose model files (e.g. encoder.onnx, decoder.onnx, tokens.txt
     * downloaded one by one from Hugging Face) as a single sherpa-onnx model.
     */
    fun importModelFiles(uris: List<Uri>, context: Context) {
        var context = context
        context = context.applicationContext
        val serviceIntent = Intent(context, FileDownloadService::class.java)
        serviceIntent.putExtra(ACTION, ACTION_IMPORT_FILES)
        serviceIntent.putParcelableArrayListExtra(IMPORT_URIS, ArrayList(uris))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}