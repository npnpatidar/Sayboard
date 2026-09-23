package com.elishaazaria.sayboard.data

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class InstalledModelReference(
    val path: String,
    val name: String,
    val type: ModelType,
    /** User display name; blank means the automatic [name]. */
    val alias: String = ""
) {
    /** Name shown in the app: user alias when set, else the automatic name. */
    val displayName: String
        get() = alias.ifBlank { name }

    /**
     * D17/U6: path stored in [path] is relative to the models dir going
     * forward (legacy absolute paths still read, confined at use).
     * Serializable shape unchanged: the relative string lives in [path].
     */
    fun toRelative(modelsDir: File): InstalledModelReference {
        return try {
            val base = modelsDir.canonicalPath
            val c = File(path).canonicalPath
            if (c.startsWith(base + File.separator)) {
                copy(path = c.removePrefix(base + File.separator))
            } else {
                this
            }
        } catch (_: Exception) {
            this
        }
    }

    /**
     * D17/U6: resolve the stored [path] (relative new, absolute legacy)
     * against [modelsDir]. Relative entries without a models dir resolve to
     * a bare relative [File] that fails confinement downstream (fail-closed).
     */
    fun resolveAbsolute(modelsDir: File?): File {
        return try {
            val f = File(path)
            if (f.isAbsolute) f else if (modelsDir != null) File(modelsDir, path) else f
        } catch (_: Exception) {
            File(path)
        }
    }

    companion object {
        /**
         * F4: relativize a pin/map key against [modelsDir] so backups never
         * carry absolute device paths. Non-absolute or foreign keys pass
         * through verbatim (legacy readers still resolve them).
         */
        fun relativeKey(modelsDir: File, key: String): String {
            return try {
                val base = modelsDir.canonicalPath
                val c = File(key).canonicalPath
                if (File(key).isAbsolute && c.startsWith(base + File.separator)) {
                    c.removePrefix(base + File.separator)
                } else {
                    key
                }
            } catch (_: Exception) {
                key
            }
        }
        /**
         * D17 restore-gate bounds (used by the backup wave on import):
         * absolute paths stay small, names/aliases stay UI-safe, and unknown
         * types are dropped before anything touches the filesystem.
         */
        const val MAX_PATH_CHARS = 256
        const val MAX_NAME_CHARS = 128
        const val MAX_ALIAS_CHARS = 64

        /** False for entries a crafted backup could have planted. */
        fun isPlausible(ref: InstalledModelReference): Boolean {
            if (ref.type == ModelType.UNKNOWN) return false
            if (ref.path.length !in 1..MAX_PATH_CHARS) return false
            if (ref.name.length !in 1..MAX_NAME_CHARS) return false
            if (ref.alias.length > MAX_ALIAS_CHARS) return false
            if ('\u0000' in ref.path || '\u0000' in ref.name || '\u0000' in ref.alias) return false
            return true
        }
    }
}