package com.elishaazaria.sayboard.recognition.recognizers.providers

import android.content.Context
import android.os.Build
import com.elishaazaria.sayboard.BuildConfig
import com.elishaazaria.sayboard.Constants
import com.elishaazaria.sayboard.Tools
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.data.SherpaEngine
import com.elishaazaria.sayboard.downloader.ArchiveTools
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.sources.SherpaParakeet
import com.elishaazaria.sayboard.recognition.recognizers.sources.SherpaStreaming
import com.elishaazaria.sayboard.recognition.recognizers.sources.SherpaWhisper
import com.elishaazaria.sayboard.utils.AppLog
import java.io.File
import java.util.Locale

/**
 * Discovers installed sherpa-onnx (Whisper/Parakeet) models: any model folder
 * under Models/<locale>/ carrying the engine marker (or sherpa model files).
 *
 * Scan hardening (R10): deterministic sorted order, canonical-path
 * containment under Models/, symlink rejection, and locale-tag validation
 * before [Locale.forLanguageTag]. Holds only the application context (R8).
 */
class SherpaProvider(context: Context) : RecognizerSourceProvider {
    private val appContext: Context = context.applicationContext

    override fun getInstalledModels(): List<InstalledModelReference> {
        val models: MutableList<InstalledModelReference> = ArrayList()
        val modelsDir = Constants.getModelsDirectory(appContext)
        if (!modelsDir.exists()) return models
        val base = runCatching { modelsDir.canonicalPath }.getOrNull() ?: return models
        val localeFolders = modelsDir.listFiles()?.sortedBy { it.name } ?: return models
        for (localeFolder in localeFolders) {
            if (!localeFolder.isDirectory) continue
            if (isSymlink(localeFolder)) continue
            if (!isUnderBase(localeFolder, base)) continue
            if (!isValidLocaleTag(localeFolder.name)) continue
            val locale = Locale.forLanguageTag(localeFolder.name)
            val modelFolders = localeFolder.listFiles()?.sortedBy { it.name } ?: emptyList()
            for (modelFolder in modelFolders) {
                if (!modelFolder.isDirectory) continue
                if (isSymlink(modelFolder)) continue
                if (!isUnderBase(modelFolder, base)) continue
                if (modelFolder.name == "Temp" || modelFolder.name == "ModelZips") continue
                // Marker first, file detection as fallback for robustness
                val sherpa = Tools.getSherpaModelFromPath(modelFolder) ?: continue
                val type = when (sherpa.engine) {
                    SherpaEngine.WHISPER -> ModelType.WhisperLocal
                    SherpaEngine.PARAKEET -> ModelType.ParakeetLocal
                    SherpaEngine.STREAMING -> ModelType.StreamingLocal
                }
                val label = when (sherpa.engine) {
                    SherpaEngine.WHISPER -> "Whisper"
                    SherpaEngine.PARAKEET -> "Parakeet"
                    SherpaEngine.STREAMING ->
                        if (ArchiveTools.isZipformerStreaming(modelFolder)) "Zipformer live"
                        else "Parakeet live"
                }
                models.add(
                    InstalledModelReference(
                        modelFolder.absolutePath,
                        "$label · ${modelFolder.name}",
                        type
                    ).toRelative(modelsDir)
                )
            }
        }
        // D19/U1: counts always persist; leaf names only in debug builds.
        // Free-form dictated text was never classified into log tiers, so
        // leaf gating (not redaction) is the fix here — transcript safety
        // rests on the upstream I-01 DEBUG-gating of text-bearing logs.
        if (BuildConfig.DEBUG) {
            AppLog.d(TAG, "scan found count=${models.size} leaves=${models.map { leafName(it.path) }}")
        } else {
            AppLog.d(TAG, "scan found count=${models.size}")
        }
        return models
    }

    override fun recognizerSourceForModel(localModel: InstalledModelReference): RecognizerSource? {
        val modelsDir = Constants.getModelsDirectory(appContext)
        val model = Tools.getSherpaModelFromReference(localModel, modelsDir) ?: return null
        // Route on the engine resolved from content just now, not the
        // persisted type: folder contents may have changed since the scan.
        return when (model.engine) {
            SherpaEngine.WHISPER -> SherpaWhisper(model)
            SherpaEngine.PARAKEET -> SherpaParakeet(model)
            SherpaEngine.STREAMING -> SherpaStreaming(model)
        }
    }

    companion object {
        private const val TAG = "SherpaProvider"

        internal fun leafName(path: String): String {
            val leaf = path.substringAfterLast('/').substringAfterLast('\\')
            return leaf.take(80)
        }

        /** BCP-47-ish gate before forLanguageTag; rejects reserved names. */
        internal fun isValidLocaleTag(name: String): Boolean {
            if (name.isEmpty() || name.length > 35) return false
            if (name == "Temp" || name == "ModelZips") return false
            if ('/' in name || '\\' in name || '.' in name) return false
            return name.matches(Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}\$"))
        }

        internal fun isUnderBase(f: File, baseCanonical: String): Boolean {
            return try {
                val c = f.canonicalPath
                c == baseCanonical || c.startsWith(baseCanonical + File.separator)
            } catch (_: Throwable) {
                false
            }
        }

        internal fun isSymlink(f: File): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    java.nio.file.Files.isSymbolicLink(f.toPath())
                } else {
                    f.canonicalFile != f.absoluteFile
                }
            } catch (_: Throwable) {
                false
            }
        }
    }
}
