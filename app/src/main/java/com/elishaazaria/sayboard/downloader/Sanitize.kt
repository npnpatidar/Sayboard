package com.elishaazaria.sayboard.downloader

import android.content.Context
import com.elishaazaria.sayboard.Constants
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Central edge-sanitization + URL-allow-list gate for the downloader (D1/D4/D17).
 *
 * - File names: strict allow-list `^[A-Za-z0-9._-]{1,80}$` ([sanitizeFilenameStrict]
 *   rejects, [flattenName] flattens SAF/DISPLAY_NAME input). The Constants wave
 *   reuses [FILENAME_PATTERN]/[flattenName] for the temp-download location.
 * - URLs: https-only + enumerated host allow-list ([sanitizeDownloadUrl]) with
 *   manual redirect handling ([openAllowedConnection]: max 3 hops, no
 *   https→http downgrade, HttpURLConnection only).
 * - Destinations: canonical-startsWith containment ([requireWithin]) and
 *   Models/ confinement ([requireModelsConfined]).
 */
object Sanitize {
    /** Edge allow-list for every intent/SAF filename. */
    val FILENAME_PATTERN = Regex("^[A-Za-z0-9._-]{1,80}$")

    /**
     * Strict edge sanitizer: takes the basename, rejects `.`/`..`/empty, and
     * requires the full allow-list match. Returns null on any violation
     * (callers fail the job, never coerce silently).
     */
    fun sanitizeFilenameStrict(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        if (base.isEmpty() || base == "." || base == "..") return null
        if (!FILENAME_PATTERN.matches(base)) return null
        return base
    }

    /**
     * Flatten an untrusted display/SAF name (DISPLAY_NAME, tree names) into a
     * safe stem. Never returns null/blank/traversal; callers uniquify with
     * [uniquify] before creating the file.
     */
    fun flattenName(raw: String?, fallback: String = "model"): String {
        var base = (raw ?: "").substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isEmpty()) base = fallback
        base = base.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        if (base.length > 80) base = base.take(80)
        base = base.trim('.')
        if (base.isEmpty() || base == "." || base == "..") base = fallback
        return base
    }

    /**
     * Dedupe [name] inside [dir] (`stem-1.ext`, …). Pure name computation;
     * callers still create the file exclusively under containment.
     */
    fun uniquify(dir: File, name: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var i = 1
        while (File(dir, candidate).exists() && i < 10_000) {
            val suffix = "-$i"
            val keep = (80 - suffix.length - ext.length).coerceAtLeast(1)
            candidate = stem.take(keep) + suffix + ext
            i++
        }
        return candidate
    }

    /** Canonical containment: [child] must live strictly inside [parent]. */
    fun isWithin(parent: File, child: File): Boolean {
        return try {
            child.canonicalPath.startsWith(parent.canonicalPath + File.separator)
        } catch (_: IOException) {
            false
        }
    }

    /** Fail-closed containment check for every install destination (D4). */
    @Throws(IOException::class)
    fun requireWithin(parent: File, child: File, what: String = "path") {
        if (!isWithin(parent, child)) throw IOException("Unsafe $what")
    }

    /** D17: install/scan/delete targets must stay under Models/. */
    fun isConfinedToModels(context: Context, file: File): Boolean {
        return try {
            isWithin(Constants.getModelsDirectory(context), file)
        } catch (_: Exception) {
            false
        }
    }

    /** Fail-closed Models/ confinement (D17). */
    @Throws(IOException::class)
    fun requireModelsConfined(context: Context, file: File, what: String = "model path") {
        if (!isConfinedToModels(context, file)) throw IOException("Unsafe $what (outside Models/)")
    }

    // ------------------------------------------------------------------
    // D1: https-only + enumerated host allow-list.
    //
    // Hosts found by grepping all ModelLink URLs + hardcoded https hosts in
    // settings-adjacent code (ModelsSettingsUi vosk-website annotation):
    //   alphacephei.com, github.com.
    // github.com release downloads redirect to these asset hosts, so the
    // same hop-by-hop allow-list must cover them or installs break:
    //   objects.githubusercontent.com, release-assets.githubusercontent.com.
    // No huggingface URLs exist in the codebase, so none are listed: adding
    // unreferenced hosts would widen the SSRF surface for no user.
    // ------------------------------------------------------------------
    // Note: `githubusercontent.com` (raw) is deliberately NOT listed — the
    // catalog never points there and SAF/file URLs are never downloads.
    // ------------------------------------------------------------------
    val ALLOWED_HOSTS = setOf(
        "alphacephei.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    const val MAX_REDIRECTS = 3
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000

    fun hostAllowed(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** Fail-closed URL gate: https + allow-listed host. */
    @Throws(IOException::class)
    fun sanitizeDownloadUrl(raw: String?): URL {
        if (raw.isNullOrEmpty()) throw IOException("Empty download URL")
        val url = try {
            URL(raw)
        } catch (e: Exception) {
            throw IOException("Bad download URL", e)
        }
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Only https downloads are allowed")
        }
        if (!hostAllowed(url.host)) throw IOException("Download host is not allow-listed")
        return url
    }

    /**
     * Open an allow-listed https connection with MANUAL redirect handling
     * (instanceFollowRedirects=false): at most [MAX_REDIRECTS] hops, every hop
     * re-checked for https + allow-listed host (no https→http downgrade), and
     * non-HttpURLConnection transports rejected. [configure] (e.g. Range) is
     * applied to every hop before its response code is read.
     *
     * The returned connection is already connected; callers must
     * `disconnect()` in a finally block. Timeouts (10s connect / 15s read)
     * are set on every hop (D7).
     */
    @Throws(IOException::class)
    fun openAllowedConnection(
        url: URL,
        configure: ((HttpURLConnection) -> Unit)? = null
    ): HttpURLConnection {
        var current = url
        var hops = 0
        while (true) {
            if (!current.protocol.equals("https", ignoreCase = true)) {
                throw IOException("Refusing non-https redirect hop")
            }
            if (!hostAllowed(current.host)) {
                throw IOException("Redirect target host is not allow-listed")
            }
            val raw = try {
                current.openConnection()
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Cannot open connection", e)
            }
            if (raw !is HttpURLConnection) {
                throw IOException("Non-HTTP connection rejected")
            }
            raw.instanceFollowRedirects = false
            raw.connectTimeout = CONNECT_TIMEOUT_MS
            raw.readTimeout = READ_TIMEOUT_MS
            try {
                configure?.invoke(raw)
            } catch (e: IOException) {
                raw.disconnect()
                throw e
            }
            val code = try {
                raw.responseCode
            } catch (e: IOException) {
                raw.disconnect()
                throw e
            }
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308
            ) {
                raw.disconnect()
                if (hops >= MAX_REDIRECTS) throw IOException("Too many redirects")
                val location = raw.getHeaderField("Location")
                    ?: throw IOException("Redirect without Location")
                try {
                    current = URL(current, location)
                } catch (e: Exception) {
                    throw IOException("Bad redirect target", e)
                }
                hops++
                continue
            }
            return raw
        }
    }

    /** Short stable binding of a resume partial to its catalog URL (D6). */
    fun partialKey(url: String): String = sha256Hex(url).take(16)

    fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val out = digest.digest(s.toByteArray(Charsets.UTF_8))
        return out.joinToString("") { "%02x".format(it) }
    }

    /** Sidecar binding a partial file to its URL + validators (D6). */
    fun metaFileFor(partial: File): File = File(partial.parent, partial.name + ".meta")

    data class PartialMeta(
        val urlHash: String,
        val validator: String?,
        val total: Long,
        val createdAt: Long
    ) {
        fun saveTo(file: File) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parent, file.name + ".tmp")
            tmp.writeText(
                "v=1\nurlHash=$urlHash\nvalidator=${validator ?: ""}\n" +
                    "total=$total\ncreatedAt=$createdAt\n",
                Charsets.UTF_8
            )
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Cannot write partial metadata")
            }
        }

        companion object {
            fun loadFrom(file: File): PartialMeta? {
                return try {
                    if (!file.isFile || file.length() > 1024) return null
                    val map = file.readLines(Charsets.UTF_8)
                        .mapNotNull {
                            val i = it.indexOf('=')
                            if (i <= 0) null else it.take(i) to it.drop(i + 1)
                        }.toMap()
                    if (map["v"] != "1") return null
                    val urlHash = map["urlHash"] ?: return null
                    PartialMeta(
                        urlHash = urlHash,
                        validator = map["validator"]?.ifEmpty { null },
                        total = map["total"]?.toLongOrNull() ?: -1L,
                        createdAt = map["createdAt"]?.toLongOrNull() ?: 0L
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
