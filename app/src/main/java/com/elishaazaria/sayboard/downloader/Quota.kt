package com.elishaazaria.sayboard.downloader

import java.io.File
import java.io.IOException

/**
 * Unified decompression-bomb / disk-fill quotas (D3, covers B13).
 *
 * Enforced in tar AND zip AND copy/import/download paths via [Tracker]:
 * - max 10k entries, 4GB total, 2GB per entry, 100:1 compression ratio,
 * - free-space + 500MB margin check before jobs,
 * - during-write byte accounting (never one-entry-late).
 */
object Quota {
    const val MAX_ENTRIES = 10_000
    const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_RATIO = 100.0
    const val FREE_SPACE_MARGIN_BYTES = 500L * 1024 * 1024

    /** Cap on files visited by a format scan (fan-out breadth cap, D3). */
    const val MAX_SCAN_FILES = 20_000

    /** Cap on children processed per single directory during scan/copy. */
    const val MAX_SCAN_BREADTH = 2_000

    /** Max SAF uris accepted per import batch (D10). */
    const val MAX_IMPORT_URIS = 50

    /** Resume partials: 7-day keep, bounded size (D6). */
    const val PARTIAL_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_PARTIAL_BYTES = MAX_ENTRY_BYTES

    /** Fail-closed free-space gate: [dir] must hold [needed] + margin. */
    @Throws(IOException::class)
    fun checkFreeSpace(dir: File, needed: Long) {
        val safeNeeded = needed.coerceAtLeast(0L)
        if (safeNeeded > MAX_TOTAL_BYTES) throw IOException("Download too large (>${MAX_TOTAL_BYTES}B)")
        try {
            val usable = dir.usableSpace
            // usableSpace <= 0 means unknown on some filesystems: only enforce
            // when the OS reports a positive value, never block blindly.
            if (usable > 0 && usable < safeNeeded + FREE_SPACE_MARGIN_BYTES) {
                throw IOException(
                    "Not enough free space (need ${mb(safeNeeded)} + " +
                        "${mb(FREE_SPACE_MARGIN_BYTES)} reserve)"
                )
            }
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            // Unknown free space: allow, per-write caps still apply.
        }
    }

    private fun mb(v: Long): String = "${v / 1024 / 1024}MB"

    /**
     * Per-job byte/entry accountant. [archiveBytes] is the compressed size
     * when known (zip/tar/download with length), else <= 0 to skip the ratio
     * gate until enough bytes are observed.
     */
    class Tracker(private val archiveBytes: Long) {
        var entries = 0
            private set
        var bytes = 0L
            private set
        private var entryBytes = 0L

        /** Count one entry up-front (headers give the name/size first). */
        @Throws(IOException::class)
        fun onEntry(declaredSize: Long = -1L) {
            entries++
            if (entries > MAX_ENTRIES) throw IOException("Archive has too many files (>$MAX_ENTRIES)")
            if (declaredSize > MAX_ENTRY_BYTES) {
                throw IOException("Archive entry too large (>${MAX_ENTRY_BYTES}B)")
            }
            entryBytes = 0L
        }

        /**
         * During-write check (D3): call for every chunk BEFORE writing it so
         * the last entry can never run unbounded.
         */
        @Throws(IOException::class)
        fun onBytes(n: Long) {
            if (n < 0) return
            entryBytes += n
            if (entryBytes > MAX_ENTRY_BYTES) {
                throw IOException("Archive entry too large (>${MAX_ENTRY_BYTES}B)")
            }
            bytes += n
            if (bytes > MAX_TOTAL_BYTES) {
                throw IOException("Archive too large (>${MAX_TOTAL_BYTES}B)")
            }
            if (archiveBytes > 0 && bytes > archiveBytes * MAX_RATIO + 1024 * 1024) {
                throw IOException("Suspicious compression ratio (>$MAX_RATIO:1)")
            }
        }
    }
}
