package com.elishaazaria.sayboard.downloader

import android.util.Log
import androidx.lifecycle.Observer
import com.elishaazaria.sayboard.BuildConfig
import com.elishaazaria.sayboard.data.SherpaEngine
import com.elishaazaria.sayboard.utils.AppLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Model formats Sayboard can install, detected from extracted files. */
enum class ModelFormat {
    VOSK,
    WHISPER,
    PARAKEET,
    UNKNOWN
}

object ArchiveTools {
    private const val TAG = "ArchiveTools"

    /**
     * D11: ONNX weight files below this size are decoys/placeholders, never
     * real checkpoints (real ones are tens-to-hundreds of MB).
     */
    const val MIN_ONNX_BYTES = 1L * 1024 * 1024

    /**
     * R1/D11 ONNX header sniff (heuristic, documented): real checkpoints are
     * protobuf (ONNX) serializations whose first field is typically the
     * IR-version varint (first byte 0x08) or a length-delimited field
     * (0x0A/0x12), and whose bytes are dense binary. Both arms are required:
     * first byte in {0x08, 0x0A, 0x12} AND printable-ASCII fraction <= 0.9
     * over the first 4KB. The printable arm is what rejects text decoys — a
     * zero-ratio arm alone would pass any zero-free text file (e.g. a ≥1MB
     * placeholder, or one starting with a newline 0x0A). Conservative by
     * design: any real checkpoint passes the first-byte arm immediately and
     * sits far below the printable ceiling (field tags and lengths
     * intersperse the embedded strings).
     */
    fun isPlausibleOnnx(file: File): Boolean {
        if (!file.isFile) return false
        val len = try {
            file.length()
        } catch (_: Exception) {
            return false
        }
        if (len < MIN_ONNX_BYTES) return false
        return isPlausibleOnnxHead(file)
    }

    /**
     * Protobuf-magic half of [isPlausibleOnnx] without the size floor, for
     * small-but-real protobuf models (e.g. the bundled VAD checkpoint,
     * which is under [MIN_ONNX_BYTES]): first-byte tag plus the printable
     * ceiling (at least 16 bytes must be readable).
     */
    fun isPlausibleOnnxHead(file: File): Boolean {
        return try {
            if (!file.isFile) return false
            FileInputStream(file).use { ins ->
                val head = ByteArray(4096)
                val n = ins.read(head)
                if (n < 16) return false
                val first = head[0]
                if (first != 0x08.toByte() && first != 0x0A.toByte() &&
                    first != 0x12.toByte()
                ) {
                    return false
                }
                var printable = 0
                for (i in 0 until n) {
                    val b = head[i].toInt() and 0xFF
                    if (b == 0x09 || b == 0x0A || b == 0x0D ||
                        (b in 0x20..0x7E)
                    ) {
                        printable++
                    }
                }
                printable * 10 <= n * 9
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * R1 Kaldi header sniff for final.mdl: non-empty AND (first byte 0x0B,
     * the Kaldi B-form binary marker, OR the Kaldi-specific ASCII tags
     * "TransitionModel"/"Nnet" within the first 4KB of the text form — a
     * bare '<' is NOT sufficient, any XML-ish decoy contains one).
     * Rejects empty/placeholder final.mdl files that pass the old
     * non-empty check.
     */
    fun isPlausibleKaldiMdl(finalMdl: File): Boolean {
        return try {
            if (!finalMdl.isFile) return false
            if (finalMdl.length() <= 0L) return false
            FileInputStream(finalMdl).use { ins ->
                val head = ByteArray(4096)
                val n = ins.read(head)
                if (n <= 0) return false
                if (head[0] == 0x0B.toByte()) return true
                val text = String(head, 0, n, Charsets.ISO_8859_1)
                text.contains("TransitionModel") || text.contains("Nnet")
            }
        } catch (_: Exception) {
            false
        }
    }

    /** D12: engine-marker reads are capped; anything larger is ignored. */
    const val MAX_MARKER_BYTES = 64L

    /**
     * Marker file written into an installed model directory to remember which
     * sherpa-onnx engine it needs. Absence of the marker means Vosk (legacy).
     */
    const val ENGINE_MARKER = ".sayboard-engine"

    fun writeEngineMarker(modelDir: File, engine: SherpaEngine) {
        try {
            File(modelDir, ENGINE_MARKER).writeText(engine.name)
        } catch (e: IOException) {
            // D12: log write failures instead of swallowing them.
            Log.e(TAG, "Failed to write engine marker", e)
            AppLog.e(TAG, "Failed to write engine marker", e)
        }
    }

    /** D11 (marker discipline): Vosk installs must not carry a sherpa marker. */
    fun deleteEngineMarker(modelDir: File) {
        try {
            File(modelDir, ENGINE_MARKER).delete()
        } catch (_: Exception) {
        }
    }

    /**
     * D12: read capped at [MAX_MARKER_BYTES]; oversize/unparseable markers
     * return null so callers fall back to the content check ([isSherpaModel]
     * → [detectFormat]) instead of misrouting the model.
     */
    fun readEngineMarker(modelDir: File): SherpaEngine? {
        return try {
            val marker = File(modelDir, ENGINE_MARKER)
            if (!marker.isFile) return null
            if (marker.length() > MAX_MARKER_BYTES) {
                Log.w(TAG, "Ignoring oversize engine marker")
                return null
            }
            val buf = ByteArray((MAX_MARKER_BYTES + 1).toInt())
            val n: Int = FileInputStream(marker).use { it.read(buf) }
            if (n <= 0 || n > MAX_MARKER_BYTES) {
                if (n > MAX_MARKER_BYTES) Log.w(TAG, "Ignoring oversize engine marker")
                return null
            }
            SherpaEngine.valueOf(String(buf, 0, n, Charsets.UTF_8).trim())
        } catch (_: Exception) {
            null
        }
    }

    fun isSherpaModel(modelDir: File): Boolean {
        if (readEngineMarker(modelDir) != null) return true
        val format = detectFormat(modelDir)
        return format == ModelFormat.WHISPER || format == ModelFormat.PARAKEET
    }

    /** Vosk archive/folder names embed the locale, e.g. vosk-model-small-en-us-0.15. */
    private val localePattern =
        java.util.regex.Pattern.compile("vosk-model-(small-)?(\\w\\w(-\\w\\w)?)-(\\w+-)?v?\\d\\.?\\d*.*")

    fun detectLocaleFromName(name: String): java.util.Locale? {
        val matcher = localePattern.matcher(name)
        if (!matcher.matches()) return null
        val tag = matcher.group(2) ?: return null
        return try {
            java.util.Locale.forLanguageTag(tag)
        } catch (_: Exception) {
            null
        }
    }

    /** Detailed scan result, used for diagnostics when detection fails. */
    data class FormatScan(
        val entries: Int,
        val foundFinalMdl: Boolean,
        val foundEncoder: Boolean,
        val foundDecoder: Boolean,
        val foundJoiner: Boolean,
        val foundTokens: Boolean,
        val topNames: List<String>
    ) {
        fun format(): ModelFormat {
            if (foundFinalMdl) return ModelFormat.VOSK
            if (foundEncoder && foundDecoder && foundTokens) {
                return if (foundJoiner) ModelFormat.PARAKEET else ModelFormat.WHISPER
            }
            return ModelFormat.UNKNOWN
        }

        fun summary(): String {
            return "entries=$entries mdl=$foundFinalMdl enc=$foundEncoder " +
                "dec=$foundDecoder join=$foundJoiner tok=$foundTokens " +
                "top=${topNames.take(8)}"
        }

        /** D19: counts/flags only — no file names — for log lines. */
        fun countSummary(): String {
            return "entries=$entries mdl=$foundFinalMdl enc=$foundEncoder " +
                "dec=$foundDecoder join=$foundJoiner tok=$foundTokens"
        }
    }

    fun scan(modelDir: File): FormatScan {
        var entries = 0
        var foundFinalMdl = false
        var foundEncoder = false
        var foundDecoder = false
        var foundJoiner = false
        var foundTokens = false
        val topNames = modelDir.listFiles()?.map { it.name } ?: emptyList()

        if (modelDir.isDirectory) {
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.add(modelDir to 0)
            scan@ while (stack.isNotEmpty()) {
                val (f, depth) = stack.removeLast()
                if (depth > 8) continue
                if (f.isDirectory) {
                    // D3: cap scan fan-out breadth; sorted for determinism.
                    val kids = try {
                        f.listFiles()?.sortedBy { it.name }?.take(Quota.MAX_SCAN_BREADTH)
                    } catch (_: Exception) {
                        null
                    } ?: emptyList()
                    kids.forEach { stack.add(it to depth + 1) }
                    continue
                }
                entries++
                if (entries > Quota.MAX_SCAN_FILES) break@scan
                val name = f.name.lowercase()
                // D11+R1: Kaldi marker must pass the header sniff, not just
                // be non-empty; implausible files are skipped (debug-logged).
                if (name == "final.mdl") {
                    try {
                        if (isPlausibleKaldiMdl(f)) {
                            foundFinalMdl = true
                        } else if (f.isFile && f.length() > 0 &&
                            BuildConfig.DEBUG
                        ) {
                            AppLog.d(TAG, "scan skipping implausible final.mdl")
                        }
                    } catch (_: Exception) {
                    }
                }
                if (name.endsWith(".onnx")) {
                    if ("encoder" in name) foundEncoder = true
                    if ("decoder" in name) foundDecoder = true
                    if ("joiner" in name) foundJoiner = true
                }
                // sherpa files are prefixed, e.g. tiny-tokens.txt
                if ("tokens" in name && name.endsWith(".txt")) foundTokens = true
            }
        }
        return FormatScan(
            entries, foundFinalMdl, foundEncoder, foundDecoder,
            foundJoiner, foundTokens, topNames
        )
    }

    /**
     * Detect the model format by scanning for known files anywhere below
     * [modelDir] (Vosk nests its marker at e.g. <model>/am/final.mdl).
     */
    fun detectFormat(modelDir: File): ModelFormat {
        return scan(modelDir).format()
    }

    /**
     * First child directory of [tempRoot]. Exactly-one-top is the norm;
     * otherwise the deterministic sorted-first pick wins (D11) — never raw
     * filesystem order.
     */
    fun findModelTopDir(tempRoot: File): File? {
        val dirs = try {
            tempRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
        } catch (_: Exception) {
            null
        } ?: return null
        return dirs.firstOrNull()
    }

    /**
     * Find a model weight file, preferring int8 quantized variants when both
     * int8 and fp32 are shipped in one archive (sherpa release tarballs
     * contain both, e.g. tiny-encoder.onnx + tiny-encoder.int8.onnx).
     * D11: `.onnx` candidates under [MIN_ONNX_BYTES] are ignored and the
     * winner is the deterministic size-DESC pick (name breaks ties).
     * R1: candidates failing [isPlausibleOnnx] are skipped (see [findFile]).
     */
    fun findOnnx(modelDir: File, infix: String): File? {
        return findFile(modelDir, "$infix.int8", ".onnx", MIN_ONNX_BYTES)
            ?: findFile(modelDir, infix, ".onnx", MIN_ONNX_BYTES)
    }

    /**
     * Small-weight lookup for transducer models (zipformer streaming,
     * Parakeet transducer): joiners are legitimately sub-MB (a real
     * 0.98 MiB zipformer joiner was refused by the floor), and stateless
     * decoder/predictors can be tens of KB (a real 25 KB file refused the
     * same way). Same exemption shape as the Silero VAD file: head-only
     * sniff (first-byte tag + printable-fraction ceiling), no size floor.
     * Encoder lookups keep [findOnnx] with the floor (encoders are always
     * hundreds of MB; placeholders there are the actual threat).
     */
    fun findOnnxSmall(modelDir: File, infix: String): File? {
        return findFile(modelDir, "$infix.int8", ".onnx", 0L, headOnlyOnnx = true)
            ?: findFile(modelDir, infix, ".onnx", 0L, headOnlyOnnx = true)
    }

    /**
     * True when [modelDir] holds a live-transcription checkpoint: either a
     * NeMo unified streaming model (decoder metadata marker) or a zipformer
     * streaming export ("chunk" in the encoder file name, e.g.
     * encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx).
     */
    fun isStreamingCheckpoint(modelDir: File): Boolean {
        return isUnifiedStreaming(modelDir) || isZipformerStreaming(modelDir)
    }

    /** Zipformer streaming exports bake the chunk config into file names. */
    fun isZipformerStreaming(modelDir: File): Boolean {
        val encoder = findOnnx(modelDir, "encoder") ?: return false
        return "chunk" in encoder.name.lowercase()
    }

    /**
     * True when [modelDir] holds a NeMo unified *streaming* checkpoint. The
     * unified decoder carries a `streaming_model_type` metadata value that
     * plain protobuf string bytes preserve, so a byte scan of the decoder
     * finds it without an ONNX parser. ONNX writes metadata after the
     * graph, so the tail is checked first; whole thing takes ~100ms.
     */
    fun isUnifiedStreaming(modelDir: File): Boolean {
        val decoder = findOnnx(modelDir, "decoder") ?: return false
        return fileContains(decoder, "nemo_parakeet_unified_streaming".toByteArray())
    }

    private fun fileContains(file: File, marker: ByteArray): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val window = 8L * 1024 * 1024
                // Tail first (metadata lives at the end), then head.
                if (rangeContains(raf, maxOf(0, len - window), len, marker)) return true
                if (len > window) {
                    return rangeContains(raf, 0, minOf(len, window), marker)
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun rangeContains(
        raf: java.io.RandomAccessFile,
        start: Long,
        end: Long,
        marker: ByteArray
    ): Boolean {
        val size = (end - start).toInt().coerceAtLeast(0)
        if (size < marker.size) return false
        val buf = ByteArray(size)
        raf.seek(start)
        raf.readFully(buf)
        outer@ for (i in 0..buf.size - marker.size) {
            for (j in marker.indices) {
                if (buf[i + j] != marker[j]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * D11: deterministic BFS (sorted children) returning the size-DESC pick
     * (name breaks ties), so decoy files can never shadow the real weights
     * via directory order. [minSizeBytes] gates placeholder files.
     */
    fun findFile(
        modelDir: File,
        infix: String,
        extension: String = "",
        minSizeBytes: Long = 0L,
        headOnlyOnnx: Boolean = false
    ): File? {
        val needle = infix.lowercase()
        val ext = extension.lowercase()
        var best: File? = null
        var bestSize = -1L
        var visited = 0
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(modelDir to 0)
        while (queue.isNotEmpty()) {
            val (f, depth) = queue.removeFirst()
            if (depth > 3) continue
            if (f.isDirectory) {
                try {
                    f.listFiles()?.sortedBy { it.name }?.take(Quota.MAX_SCAN_BREADTH)
                        ?.forEach { queue.add(it to depth + 1) }
                } catch (_: Exception) {
                }
            } else {
                if (++visited > Quota.MAX_SCAN_FILES) break
                val name = f.name.lowercase()
                if (needle in name && (ext.isEmpty() || name.endsWith(ext))) {
                    // R1/D11: .onnx candidates must pass the header sniff;
                    // failures are skipped (debug-logged) so decoys can
                    // never shadow real weights; scanning continues.
                    // headOnlyOnnx (joiner/small weights): head sniff only,
                    // no size floor — see findOnnxSmall.
                    val plausible = if (headOnlyOnnx) isPlausibleOnnxHead(f) else isPlausibleOnnx(f)
                    if (name.endsWith(".onnx") && !plausible) {
                        if (BuildConfig.DEBUG) {
                            AppLog.d(TAG, "findFile skipping implausible onnx")
                        }
                        continue
                    }
                    val size = try {
                        f.length()
                    } catch (_: Exception) {
                        0L
                    }
                    if (size >= minSizeBytes && (best == null || size > bestSize ||
                                (size == bestSize && f.name < best.name))
                    ) {
                        best = f
                        bestSize = size
                    }
                }
            }
        }
        return best
    }

    /**
     * Extract a .tar.bz2 archive into [destDir], guarding against zip-slip.
     * Progress is reported as bytes extracted / archive size.
     *
     * D3: unified [Quota.Tracker] — single entry increment per header (the
     * old double-increment halved the effective cap), during-write byte
     * accounting, per-entry cap, ratio gate, free-space pre-check.
     */
    @Throws(IOException::class)
    fun extractTarBz2(
        archive: File,
        destDir: File,
        progressObserver: Observer<Double>,
        isCancelled: () -> Boolean = { false }
    ) {
        if (!destDir.exists()) destDir.mkdirs()
        Quota.checkFreeSpace(destDir, 0)
        val destCanonical = destDir.canonicalPath
        val total = archive.length().toDouble().coerceAtLeast(1.0)
        var extracted = 0L
        var lastReported = 0.0
        // Count compressed bytes read for progress. bzip2 consumes the
        // underlying stream byte-by-byte in bulk, so throttle reports to
        // 0.5% steps — unthrottled this floods EventBus/LiveData (~100M
        // posts for a 116MB archive) and hangs the app.
        fun report() {
            val d = (extracted / total).coerceIn(0.0, 1.0)
            if (d - lastReported >= 0.005 || d >= 1.0) {
                lastReported = d
                progressObserver.onChanged(d)
            }
        }
        val counting = object : BufferedInputStream(FileInputStream(archive)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    extracted += n
                    report()
                }
                return n
            }

            override fun read(): Int {
                val v = super.read()
                if (v >= 0) {
                    extracted += 1
                    report()
                }
                return v
            }
        }
        counting.use { fileIn ->
            BZip2CompressorInputStream(fileIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextEntry
                    val tracker = Quota.Tracker(archive.length())
                    var skippedLinks = 0
                    val buf = ByteArray(32 * 1024)
                    while (entry != null) {
                        if (isCancelled()) throw IOException("Canceled")
                        // Single increment per entry (D3 fix: was counted twice,
                        // halving the effective entry cap).
                        tracker.onEntry()
                        val e = entry
                        if (e.isSymbolicLink || e.isLink) {
                            // Never materialize links from untrusted archives.
                            skippedLinks++
                            entry = tarIn.nextEntry
                            continue
                        }
                        val outFile = File(destDir, e.name)
                        // Zip-slip guard
                        if (!outFile.canonicalPath.startsWith(destCanonical + File.separator)) {
                            throw IOException("Archive entry outside destination")
                        }
                        if (e.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                                var n: Int
                                while (tarIn.read(buf).also { n = it } >= 0) {
                                    if (isCancelled()) throw IOException("Canceled")
                                    // During-write check (D3): enforced before
                                    // the bytes land, never one-entry-late.
                                    tracker.onBytes(n.toLong())
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        entry = tarIn.nextEntry
                    }
                    if (BuildConfig.DEBUG) {
                        AppLog.d(
                            TAG,
                            "extract done entries=${tracker.entries} " +
                                "bytes=${tracker.bytes} skippedLinks=$skippedLinks"
                        )
                    }
                }
            }
        }
        progressObserver.onChanged(1.0)
    }

    /** True for bzip2 ("BZh") streams, e.g. sherpa-onnx .tar.bz2 archives. */
    fun isTarBz2(file: File): Boolean {
        return try {
            FileInputStream(file).use { ins ->
                val magic = ByteArray(3)
                if (ins.read(magic) != 3) return false
                magic[0] == 'B'.code.toByte() &&
                    magic[1] == 'Z'.code.toByte() &&
                    magic[2] == 'h'.code.toByte()
            }
        } catch (e: IOException) {
            false
        }
    }

    /** True for PK zip archives. */
    fun isZip(file: File): Boolean {
        return try {
            FileInputStream(file).use { ins ->
                val magic = ByteArray(2)
                if (ins.read(magic) != 2) return false
                magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
            }
        } catch (e: IOException) {
            false
        }
    }
}
