package com.elishaazaria.sayboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Last-resort crash screen. Runs in a separate process (see manifest) so it
 * can be shown even when the main process died, using framework views only.
 * Lets the user copy/share the stack trace without needing adb or a PC.
 *
 * U3: shows a warning that the report may contain dictated text, and marks
 * the share intent sensitive. U5: sharing sends a truncated 4KB preview
 * through FileProvider (`${applicationId}.crashprovider`); the full report
 * never leaves private storage.
 */
class CrashReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra(EXTRA_TRACE) ?: "(no crash info)"

        val traceView = TextView(this).apply {
            text = trace
            textSize = 12f
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(
                traceView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val copyButton = Button(this).apply {
            text = getString(R.string.crash_copy)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("sayboard-crash", trace))
                Toast.makeText(this@CrashReportActivity, R.string.crash_copied, Toast.LENGTH_SHORT)
                    .show()
            }
        }
        val shareButton = Button(this).apply {
            text = getString(R.string.crash_share)
            setOnClickListener {
                sharePreview(trace)
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                copyButton,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                shareButton,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(
                TextView(context).apply {
                    text = getString(R.string.crash_title)
                    textSize = 18f
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            // U3: warn before the user copies/shares dictated text.
            addView(
                TextView(context).apply {
                    text = getString(R.string.crash_share_warning)
                    textSize = 14f
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(
                buttons,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(root)
    }

    /**
     * U5: write a 4KB preview (with a truncation marker when cut) to
     * cacheDir/crash/ and share it via FileProvider with read-grant flags.
     * The full report stays file-private and is never attached.
     */
    private fun sharePreview(trace: String) {
        try {
            val bytes = trace.toByteArray(Charsets.UTF_8)
            val truncated = bytes.size > PREVIEW_BYTES
            val preview = if (truncated) {
                bytes.copyOf(PREVIEW_BYTES).toString(Charsets.UTF_8) +
                    "\n\n[truncated: showing first 4KB of ${bytes.size} bytes]"
            } else {
                trace
            }
            val dir = File(cacheDir, "crash").apply { mkdirs() }
            val file = File(dir, "crash-preview.txt")
            file.writeText(preview, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(
                this, "$packageName.crashprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, preview.take(512))
                putExtra("android.intent.extra.IS_SENSITIVE", true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "crash-preview", uri)
            }
            startActivity(Intent.createChooser(send, getString(R.string.crash_share)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_TRACE = "trace"
        private const val PREVIEW_BYTES = 4096
    }
}
