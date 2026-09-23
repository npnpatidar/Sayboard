package com.elishaazaria.sayboard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.BottomNavigation
import androidx.compose.material.BottomNavigationItem
import androidx.compose.material.Icon
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.MutableLiveData
import com.elishaazaria.sayboard.downloader.FileDownloader
import com.elishaazaria.sayboard.sayboardPreferenceModel
import com.elishaazaria.sayboard.theme.AppTheme
import com.elishaazaria.sayboard.ui.GrantPermissionUi
import com.elishaazaria.sayboard.ui.KeyboardSettingsUi
import com.elishaazaria.sayboard.ui.LogicSettingsUi
import com.elishaazaria.sayboard.ui.ModelsSettingsUi
import com.elishaazaria.sayboard.ui.UISettingsUi

class SettingsActivity : ComponentActivity() {

    private val micGranted = MutableLiveData<Boolean>(true)
    private val imeGranted = MutableLiveData<Boolean>(true)

    private val modelSettingsUi = ModelsSettingsUi(this)
    private val prefs by sayboardPreferenceModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35+ enforces edge-to-edge; draw behind system bars and pad
        // content below/above them so nothing hides under status/nav bars
        enableEdgeToEdge()
        Tools.createNotificationChannel(this)

        checkPermissions()

        modelSettingsUi.onCreate()

        setContent {
            AppTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars)
                ) {
                    val micGrantedState = micGranted.observeAsState(true)
                    val imeGrantedState = imeGranted.observeAsState(true)
                    if (micGrantedState.value && imeGrantedState.value) {
                        MainUi()
                    } else {
                        GrantPermissionUi(mic = micGrantedState, ime = imeGrantedState, requestMic = {
                            ActivityCompat.requestPermissions(
                                this@SettingsActivity, arrayOf(
                                    Manifest.permission.RECORD_AUDIO
                                ), PERMISSIONS_REQUEST_RECORD_AUDIO
                            )
                        }) {
                            startActivity(Intent("android.settings.INPUT_METHOD_SETTINGS"))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MainUi() {
        val tabs = listOf<String>(
            stringResource(id = R.string.title_models),
            stringResource(id = R.string.title_ui),
            stringResource(id = R.string.title_keyboard),
            stringResource(id = R.string.title_logic)
        )
        var selectedIndex by remember {
            mutableIntStateOf(0)
        }

        Scaffold(bottomBar = {
            BottomNavigation() {
                tabs.forEachIndexed { index, tab ->
                    BottomNavigationItem(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        icon = {
                            when (index) {
                                0 -> Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = null
                                )

                                1 -> Icon(
                                    painter = painterResource(id = R.drawable.ic_baseline_color_lens_24),
                                    contentDescription = null
                                )

                                2 -> Icon(
                                    imageVector = Icons.Default.Keyboard,
                                    contentDescription = null
                                )

                                3 -> Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null
                                )
                            }
                        }, label = {
                            Text(text = tab)
                        })
                }
            }
        }, floatingActionButton = {
            if (selectedIndex == 0) {
                modelSettingsUi.Fab()
            }
        }) {
            Box(
                modifier = Modifier
                    .padding(it)
                    .padding(10.dp)
            ) {
                when (selectedIndex) {
                    0 -> modelSettingsUi.Content()
                    1 -> UISettingsUi()
                    2 -> KeyboardSettingsUi()
                    3 -> LogicSettingsUi(this@SettingsActivity)
                }
            }
        }
    }

    fun importModel() {
        val intent = Intent()
        // Single .zip/.tar.bz2 archives as well as loose Whisper/Parakeet
        // files (encoder.onnx + decoder.onnx + tokens.txt): allow multi-pick.
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.action = Intent.ACTION_GET_CONTENT
        //launch picker screen
        resultLauncher.launch(intent)
    }

    fun importModelFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        //launch folder picker screen
        folderLauncher.launch(intent)
    }

    private var resultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // There are no request codes
                val intent: Intent = result.data ?: return@registerForActivityResult
                val clipData = intent.clipData
                if (clipData != null && clipData.itemCount > 1) {
                    val uris = (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }
                    FileDownloader.importModelFiles(uris, this)
                } else {
                    // RESULT_OK with neither clipData nor data happens on
                    // some pickers: skip instead of crashing on !!.
                    val uri = intent.data
                        ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                        ?: return@registerForActivityResult
                    FileDownloader.importModel(uri, this)
                }
            }
        }

    private var folderLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val intent: Intent = result.data ?: return@registerForActivityResult
                val uri = intent.data ?: return@registerForActivityResult
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w("SettingsActivity", "persistable permission failed", e)
                }
                FileDownloader.importModelFolder(uri, this)
            }
        }

    fun exportBackup() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        backupLauncher.launch("sayboard-backup-$stamp.json")
    }

    fun importBackup() {
        restoreLauncher.launch("application/json")
    }

    /** U1 "Export log": writes the private session logs via SAF. */
    fun exportLog() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        logExportLauncher.launch("sayboard-log-$stamp.txt")
    }

    private var backupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val json = com.elishaazaria.sayboard.backup.ConfigBackupIO.export(prefs)
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray())
                }
                Toast.makeText(this, R.string.backup_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("SettingsActivity", "backup failed", e)
                Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show()
            }
        }

    /** U1: export the private session logs to a user-picked document. */
    private var logExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val files = com.elishaazaria.sayboard.utils.AppLog.exportFiles()
                if (files.isEmpty()) throw IllegalStateException("No log files")
                contentResolver.openOutputStream(uri)?.use { out ->
                    for (f in files) {
                        try {
                            f.inputStream().use { ins -> ins.copyTo(out) }
                        } catch (_: Exception) {
                        }
                    }
                }
                Toast.makeText(this, R.string.models_log_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("SettingsActivity", "log export failed", e)
                Toast.makeText(this, R.string.models_log_failed, Toast.LENGTH_LONG).show()
            }
        }

    private var restoreLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                // Cap the read: a "json" file can be anything, and readBytes
                // on a 500 MB pick would OOM.
                val raw = contentResolver.openInputStream(uri)?.use { ins ->
                    val cap = ByteArray(MAX_RESTORE_BYTES + 1)
                    var total = 0
                    var n: Int
                    while (ins.read(cap, total, cap.size - total).also { n = it } > 0) {
                        total += n
                        if (total > MAX_RESTORE_BYTES) {
                            throw IllegalArgumentException("Backup file too large")
                        }
                    }
                    cap.copyOf(total).toString(Charsets.UTF_8)
                } ?: throw IllegalArgumentException("Cannot read file")
                // U7/I-11-restore: validate the backed-up default IME against
                // the currently enabled list; unknown ids are skipped.
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                val enabledIds = try {
                    imm.enabledInputMethodList.map { it.id }
                } catch (_: Exception) {
                    null
                }
                val report = com.elishaazaria.sayboard.backup.ConfigBackupIO.restore(
                    raw, prefs, enabledIds,
                    com.elishaazaria.sayboard.Constants.getModelsDirectory(this)
                )
                com.elishaazaria.sayboard.services.KeepAlive.sync(
                    this, prefs.logicKeepAliveService.get()
                )
                modelSettingsUi.onResume()
                Toast.makeText(this, report, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("SettingsActivity", "restore failed", e)
                Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_LONG).show()
            }
        }

    private fun checkPermissions() {
        micGranted.postValue(Tools.isMicrophonePermissionGranted(this))
        imeGranted.postValue(Tools.isIMEEnabled(this))
    }

    override fun onStart() {
        super.onStart()
        modelSettingsUi.onStart()
    }

    override fun onStop() {
        modelSettingsUi.onStop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        modelSettingsUi.onResume()
    }

    companion object {
        /* Used to handle permission request */
        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 1
        public const val PERMISSION_REQUEST_POST_NOTIFICATIONS = 1
        /** Backup files are small JSON; anything larger is not ours. */
        private const val MAX_RESTORE_BYTES = 2 * 1024 * 1024

//        private const val FILE_PICKER_REQUEST_CODE = 1
    }
}