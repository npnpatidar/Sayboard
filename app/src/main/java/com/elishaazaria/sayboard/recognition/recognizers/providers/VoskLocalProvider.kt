package com.elishaazaria.sayboard.recognition.recognizers.providers

import android.content.Context
import android.os.Build
import com.elishaazaria.sayboard.Constants
import com.elishaazaria.sayboard.Tools
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.downloader.ArchiveTools
import com.elishaazaria.sayboard.downloader.Sanitize
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.utils.AppLog
import com.elishaazaria.sayboard.recognition.recognizers.sources.VoskLocal
import java.io.File
import java.util.Locale

/**
 * Discovers installed Vosk models under Models/<locale>/.
 *
 * Claim rule (R10): a folder is Vosk only when it carries the exact
 * `final.mdl` marker with an `am/` directory present
 * (`<model>/am/final.mdl`, legacy top-level `<model>/final.mdl` + `am/`
 * accepted). Any non-Sherpa dir is NOT claimed. Scan is deterministically
 * sorted with canonical-path containment, symlink rejection, and
 * locale-tag validation before [Locale.forLanguageTag].
 * Holds only the application context (R8).
 */
class VoskLocalProvider(context: Context) : RecognizerSourceProvider {
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
                // sherpa-onnx (Whisper/Parakeet) models are owned by SherpaProvider
                if (ArchiveTools.isSherpaModel(modelFolder)) continue
                // Vosk claim requires the exact final.mdl marker + am/ dir.
                if (!hasVoskMarker(modelFolder)) continue
//                val name = modelFolder.name
                val model = InstalledModelReference(
                    modelFolder.absolutePath,
                    locale.displayName,
                    ModelType.VoskLocal
                ).toRelative(modelsDir)
                models.add(model)
            }
        }
        return models
    }

    override fun recognizerSourceForModel(localModel: InstalledModelReference): RecognizerSource? {
        // Re-verify the Vosk marker at resolve time (TOCTOU between scan
        // and load must not promote a swapped-in non-model dir). Stored
        // paths may be relative: resolve against modelsDir and confine.
        val modelsDir = Constants.getModelsDirectory(appContext)
        val dir = runCatching {
            val abs = localModel.resolveAbsolute(modelsDir)
            Sanitize.requireWithin(modelsDir, abs)
            abs
        }.getOrNull() ?: return null
        if (!dir.isDirectory || !hasVoskMarker(dir)) return null
        return VoskLocal(Tools.getVoskModelFromReference(localModel, modelsDir) ?: return null)
    }

    companion object {
        private const val TAG = "VoskLocalProvider"

        /**
         * Exact-name Vosk marker with am/ presence: <model>/am/final.mdl,
         * or legacy <model>/final.mdl when <model>/am/ exists.
         *
         * R1: the marker file must additionally pass
         * [ArchiveTools.isPlausibleKaldiMdl]; implausible markers are
         * rejected with a clear error (leaf name only, never a path).
         */
        internal fun hasVoskMarker(modelDir: File): Boolean {
            return try {
                if (!modelDir.isDirectory) return false
                val leaf = modelDir.name.take(80)
                val amFinal = File(modelDir, "am/final.mdl")
                if (amFinal.isFile) {
                    if (!ArchiveTools.isPlausibleKaldiMdl(amFinal)) {
                        AppLog.e(TAG, "vosk magic rejected leaf=$leaf")
                        return false
                    }
                    return true
                }
                val topFinal = File(modelDir, "final.mdl")
                val amDir = File(modelDir, "am")
                if (!(topFinal.isFile && amDir.isDirectory)) return false
                if (!ArchiveTools.isPlausibleKaldiMdl(topFinal)) {
                    AppLog.e(TAG, "vosk magic rejected leaf=$leaf")
                    return false
                }
                true
            } catch (_: Throwable) {
                false
            }
        }

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
