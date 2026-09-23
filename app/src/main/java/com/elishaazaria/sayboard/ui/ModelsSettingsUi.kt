package com.elishaazaria.sayboard.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.elishaazaria.sayboard.BuildConfig
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.SettingsActivity
import com.elishaazaria.sayboard.Constants
import com.elishaazaria.sayboard.Tools
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.utils.AppLog
import com.elishaazaria.sayboard.data.ModelLink
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.data.SherpaEngine
import com.elishaazaria.sayboard.downloader.FileDownloader
import com.elishaazaria.sayboard.downloader.Sanitize
import com.elishaazaria.sayboard.downloader.messages.CancelCurrent
import com.elishaazaria.sayboard.downloader.messages.CancelFinished
import com.elishaazaria.sayboard.downloader.messages.CancelPending
import com.elishaazaria.sayboard.downloader.messages.DownloadError
import com.elishaazaria.sayboard.downloader.messages.DownloadProgress
import com.elishaazaria.sayboard.downloader.messages.DownloadState
import com.elishaazaria.sayboard.downloader.messages.ModelInfo
import com.elishaazaria.sayboard.downloader.messages.State
import com.elishaazaria.sayboard.downloader.messages.Status
import com.elishaazaria.sayboard.downloader.messages.StatusQuery
import com.elishaazaria.sayboard.downloader.messages.UnzipProgress
import com.elishaazaria.sayboard.recognition.recognizers.providers.Providers
import com.elishaazaria.sayboard.sayboardPreferenceModel
import dev.patrickgold.jetpref.datastore.model.observeAsState
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

data class DownloadModelProgress(
    val info: ModelInfo, var state: State, var progress: Float
) {
    fun withProgress(newProgress: Float): DownloadModelProgress {
        return DownloadModelProgress(info, state, newProgress)
    }

    fun withState(newState: State): DownloadModelProgress {
        return DownloadModelProgress(info, newState, progress)
    }
}

class ModelsSettingsUi(private val activity: SettingsActivity) {
    private val prefs by sayboardPreferenceModel()

    private val modelsPendingDownloadLD = MutableLiveData<List<ModelInfo>>(mutableListOf())
    private val modelsPendingDownload = mutableListOf<ModelInfo>()

    private val currentDownloadingModel = MutableLiveData<DownloadModelProgress?>()

    /** Catalog base names with a newer file upstream than the install. */
    private val updatesAvailableLD = MutableLiveData<Set<String>>(emptySet())
    private val checkingUpdatesLD = MutableLiveData(false)

    private var renameTarget by mutableStateOf<InstalledModelReference?>(null)
    private var renameText by mutableStateOf("")

    private val recognizerSourceProviders = Providers(activity)

    fun onCreate() {
        reloadModels()
    }

    private fun reloadModels() {
        if (BuildConfig.DEBUG) Log.d(TAG, "Reloading Models")
        val currentModels = prefs.modelsOrder.get().toMutableList()
        // Models are keyed by install path: renames (alias edits) and
        // engine re-detections must not lose order position.
        val installedByPath = recognizerSourceProviders.installedModels()
            .associateBy { it.path }
        currentModels.removeAll { it.path !in installedByPath }
        for (model in installedByPath.values) {
            val index = currentModels.indexOfFirst { it.path == model.path }
            if (index < 0) {
                currentModels.add(model)
            } else {
                // Refresh automatic name/type, keep the user alias.
                currentModels[index] = model.copy(alias = currentModels[index].alias)
            }
        }
        prefs.modelsOrder.set(currentModels)
    }


    @Composable
    fun Content() {
        val modelsPendingDownload by modelsPendingDownloadLD.observeAsState(mutableListOf())
        val currentDownloadingModel by currentDownloadingModel.observeAsState()
        val modelOrder by prefs.modelsOrder.observeAsState()
        val unpinned by prefs.unpinnedModels.observeAsState()

        var modelOrderData by remember {
            mutableStateOf(modelOrder)
        }
        modelOrderData = modelOrder

        fun moveModel(fromIndex: Int, toIndex: Int) {
            if (fromIndex < 0 || toIndex < 0 || fromIndex >= modelOrderData.size || toIndex >= modelOrderData.size) return
            prefs.modelsOrder.set(modelOrderData.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            })
        }
        // U2/U13: no recomposition content logs (counts only, DEBUG-gated).
        if (BuildConfig.DEBUG) Log.d(TAG, "models count: ${modelOrderData.size}")

        renameTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                confirmButton = {
                    Button(onClick = {
                        // U14: 32-char cap + strip \n\r/bidi on rename.
                        val clean = sanitizeAlias(renameText)
                        prefs.modelsOrder.set(
                            prefs.modelsOrder.get().map {
                                if (it.path == target.path) it.copy(alias = clean)
                                else it
                            }
                        )
                        renameTarget = null
                    }) {
                        Text(text = stringResource(id = R.string.button_confirm))
                    }
                },
                dismissButton = {
                    Button(onClick = { renameTarget = null }) {
                        Text(text = stringResource(id = R.string.button_cancel))
                    }
                },
                title = {
                    Text(text = stringResource(id = R.string.models_alias_dialog_title))
                },
                text = {
                    TextField(
                        value = renameText,
                        onValueChange = {
                            // U14: strip newlines at entry; full sanitize at confirm.
                            renameText = it.filter { c -> c != '\n' && c != '\r' }.take(64)
                        },
                        label = {
                            Text(text = stringResource(id = R.string.models_alias_dialog_label))
                        },
                        placeholder = { Text(text = target.name) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // U1: export the private diagnostic log via SAF.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { activity.exportLog() }) {
                        Text(text = stringResource(id = R.string.models_export_log))
                    }
                }
            }
            item {
                currentDownloadingModel?.let { current ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    ) {
                        Card {
                            Column {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = current.info.locale.displayName,
                                            fontSize = 20.sp
                                        )
                                        Text(text = current.info.url, fontSize = 12.sp)
                                        val stateText = stringResource(
                                            id = when (current.state) {
                                                State.NONE -> R.string.models_download_state_unknown
                                                State.QUEUED -> R.string.models_download_state_pending
                                                State.DOWNLOAD_STARTED -> R.string.models_download_state_download_started
                                                State.DOWNLOAD_FINISHED -> R.string.models_download_state_download_finished
                                                State.UNZIP_STARTED -> R.string.models_download_state_unzip_started
                                                State.UNZIP_FINISHED -> R.string.models_download_state_unzip_finished
                                                State.FINISHED -> R.string.models_download_state_finished
                                                State.ERROR -> R.string.models_download_state_error
                                                State.CANCELED -> R.string.models_download_state_canceled
                                                // D5-FAILED: terminal install failure posted by
                                                // the service (setFailed/failOrCancel).
                                                State.FAILED -> R.string.models_download_state_failed
                                            }
                                        )

                                        Text(
                                            text = stringResource(id = R.string.models_download_state).format(
                                                stateText
                                            ), fontSize = 14.sp
                                        )
                                    }
                                    IconButton(onClick = {
                                        EventBus.getDefault().post(
                                            CancelCurrent(current.info)
                                        )
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = stringResource(
                                                id = R.string.desc_cancel_download
                                            )
                                        )
                                    }
                                }
                                LinearProgressIndicator(
                                    current.progress, modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
            items(items = modelsPendingDownload) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {

                    Card {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = it.locale.displayName, fontSize = 20.sp)
                                Text(text = it.url, fontSize = 12.sp)
                                Text(
                                    text = stringResource(id = R.string.models_pending_download_state),
                                    fontSize = 14.sp
                                )
                            }
                            IconButton(onClick = {
                                EventBus.getDefault().post(
                                    CancelPending(it)
                                )
                            }) {
                                Icon(imageVector = Icons.Default.Cancel, contentDescription = stringResource(id = R.string.desc_cancel_download))
                            }
                        }
                    }
                }
            }

            item {
                if (currentDownloadingModel != null || modelsPendingDownload.isNotEmpty()) {
                    Spacer(
                        modifier = Modifier
                            .padding(10.dp, 5.dp)
                            .background(MaterialTheme.colors.primary)
                            .fillMaxWidth()
                            .height(2.dp)
                    )
                }
            }

            items(modelOrderData, { it.path }) { item ->
                val index = modelOrderData.indexOf(item)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                            .padding(10.dp)
                    ) {

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.displayName,
                                fontSize = 20.sp
                            )
                            // Recursive walk of a 100-600 MB tree: measured once
                            // per row off the main thread, never in remember().
                            var sizeLabel by remember(item.path) { mutableStateOf("") }
                            LaunchedEffect(item.path) {
                                sizeLabel = withContext(Dispatchers.IO) {
                                    // Stored paths may be relative: resolve before sizing.
                                    val abs = item.resolveAbsolute(
                                        Constants.getModelsDirectory(activity)
                                    ).absolutePath
                                    Tools.modelSizeLabel(abs)
                                }
                            }
                            if (sizeLabel.isNotEmpty()) {
                                Text(text = sizeLabel, fontSize = 12.sp)
                            }
                            Text(
                                text = when (item.type) {
                                    ModelType.VoskLocal -> stringResource(id = R.string.models_engine_vosk)
                                    ModelType.WhisperLocal -> stringResource(id = R.string.models_engine_whisper)
                                    ModelType.ParakeetLocal -> stringResource(id = R.string.models_engine_parakeet)
                                    ModelType.StreamingLocal -> stringResource(id = R.string.models_engine_live)
                                    // Tampered/foreign payloads decode here (D18);
                                    // never crash the list, show raw safely.
                                    ModelType.UNKNOWN -> stringResource(id = R.string.models_download_state_unknown)
                                }, fontSize = 12.sp
                            )
                            // D2-badge: hash badge from the download record —
                            // a validator triple means re-verified, a bare
                            // pair is trust-on-first-use.
                            hashBadge(item)?.let { badge ->
                                Text(text = badge, fontSize = 12.sp)
                            }
                            Text(text = item.path, fontSize = 12.sp)
                        }
                        IconButton(
                            onClick = { moveModel(index, index - 1) },
                            enabled = index > 0
                        ) {
                            Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = stringResource(id = R.string.desc_move_up))
                        }
                        IconButton(
                            onClick = { moveModel(index, index + 1) },
                            enabled = index < modelOrderData.size - 1
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(id = R.string.desc_move_down)
                            )
                        }
                        IconButton(onClick = {
                            renameTarget = item
                            renameText = item.alias
                        }) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = stringResource(id = R.string.desc_rename))
                        }
                        // Per-model RAM pin: pinned models stay resident once
                        // loaded (instant switches); unpin to free on switch.
                        // Default pinned (absent from the unpinned map).
                        // F3: pinning runs a sum-aware RAM guard off-main-thread;
                        // unpinning (freeing) always applies immediately. F4:
                        // keys stored relative; both forms removed on re-pin.
                        IconButton(onClick = {
                            runCatching {
                                val modelsDir = Constants.getModelsDirectory(activity)
                                val abs = item.resolveAbsolute(modelsDir).absolutePath
                                val cur = prefs.unpinnedModels.get().toMutableMap()
                                if (!com.elishaazaria.sayboard.Tools.isPathPinned(cur, modelsDir, abs)) {
                                    cur.keys.toList().forEach { k ->
                                        if (com.elishaazaria.sayboard.Tools.pinKeyMatches(modelsDir, k, abs)) cur.remove(k)
                                    }
                                    prefs.unpinnedModels.set(cur)
                                } else {
                                    kotlin.concurrent.thread {
                                        val ok = runCatching {
                                            val pinnedAbs = modelOrderData.map {
                                                it.resolveAbsolute(modelsDir).absolutePath
                                            }.filter { p ->
                                                p != abs && com.elishaazaria.sayboard.Tools.isPathPinned(cur, modelsDir, p)
                                            }
                                            com.elishaazaria.sayboard.Tools.residentFitsRam(activity, abs, pinnedAbs)
                                        }.getOrDefault(true)
                                        activity.runOnUiThread {
                                            if (ok) {
                                                runCatching {
                                                    val upd = prefs.unpinnedModels.get().toMutableMap()
                                                    upd[com.elishaazaria.sayboard.data.InstalledModelReference.relativeKey(modelsDir, abs)] = "1"
                                                    prefs.unpinnedModels.set(upd)
                                                }
                                            } else {
                                                Toast.makeText(activity, R.string.models_pin_refused_ram, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            }
                        }) {
                            // F4: dual-form pin lookup (relative stored, absolute legacy).
                            val pinned = runCatching {
                                val dir = Constants.getModelsDirectory(activity)
                                com.elishaazaria.sayboard.Tools.isPathPinned(
                                    unpinned ?: emptyMap(), dir,
                                    item.resolveAbsolute(dir).absolutePath
                                )
                            }.getOrDefault(!(unpinned?.containsKey(item.path) ?: false))
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = stringResource(id = R.string.models_keep_in_ram),
                                tint = if (pinned) MaterialTheme.colors.primary
                                else MaterialTheme.colors.onSurface.copy(alpha = 0.38f)
                            )
                        }
                        IconButton(onClick = {
                            Tools.deleteModel(item, activity)
                            reloadModels()
                        }) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(id = R.string.desc_delete))
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterialApi::class)
    @Composable
    fun Fab() {
        val modelOrder by prefs.modelsOrder.observeAsState()
        val updatesAvailable by updatesAvailableLD.observeAsState(emptySet())
        var showDownloadDialog by remember {
            mutableStateOf(false)
        }

        var showImportDialog by remember {
            mutableStateOf(false)
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FloatingActionButton(onClick = {
                showDownloadDialog = true

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissionCheck = ContextCompat.checkSelfPermission(
                        activity.applicationContext, Manifest.permission.POST_NOTIFICATIONS
                    )
                    if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(
                            activity, arrayOf(
                                Manifest.permission.POST_NOTIFICATIONS
                            ), SettingsActivity.PERMISSION_REQUEST_POST_NOTIFICATIONS
                        )
                    }
                }
            }) {
                Icon(imageVector = Icons.Default.Download, contentDescription = stringResource(id = R.string.desc_download_model))
            }

            FloatingActionButton(onClick = {
                showImportDialog = true
            }) {
                Icon(imageVector = Icons.Default.Folder, contentDescription = stringResource(id = R.string.desc_import_model))
            }

            FloatingActionButton(
                onClick = { checkForUpdates() },
            ) {
                Icon(
                    imageVector = Icons.Default.Update,
                    contentDescription = stringResource(id = R.string.models_check_updates)
                )
            }
        }

        if (showDownloadDialog) {
            JetPrefAlertDialog(
                title = stringResource(id = R.string.models_download_dialog_title),
                onDismiss = { showDownloadDialog = false },
                dismissLabel = stringResource(id = R.string.button_cancel),
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                val available = ModelLink.values().filter { ml ->
                    modelOrder.none {
                        it.path.endsWith("/" + ml.archiveBaseName)
                    }
                }
                val card: @Composable (ModelLink) -> Unit = { ml ->
                    Card(
                        onClick = {
                            // D16: offline mode blocks download starts.
                            if (prefs.logicOfflineMode.get()) {
                                Toast.makeText(
                                    activity,
                                    activity.getString(R.string.logic_offline_blocked),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                showDownloadDialog = false
                                FileDownloader.downloadModel(
                                    ml, activity,
                                    fresh = ml.archiveBaseName in updatesAvailable
                                )
                            }
                        },
                        modifier = Modifier
                            .background(MaterialTheme.colors.onSurface.copy(0.2f))
                            .fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .background(MaterialTheme.colors.onSurface.copy(0.2f))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (ml.engine == null) ml.locale.displayName
                                    else {
                                        val engineLabel = when (ml.engine) {
                                            SherpaEngine.WHISPER ->
                                                stringResource(id = R.string.models_engine_whisper)
                                            SherpaEngine.PARAKEET ->
                                                stringResource(id = R.string.models_engine_parakeet)
                                            SherpaEngine.STREAMING ->
                                                stringResource(id = R.string.models_engine_live)
                                        }
                                        val prefix = if (ml.streaming) {
                                            stringResource(id = R.string.models_engine_live)
                                        } else {
                                            engineLabel
                                        }
                                        val size = if (ml.sizeHint.isNotEmpty()) " (${ml.sizeHint})" else ""
                                        "$prefix · ${ml.archiveBaseName}$size"
                                    }
                                )
                                Text(text = ml.link, fontSize = 10.sp)
                                if (ml.archiveBaseName in updatesAvailable) {
                                    Text(
                                        text = stringResource(
                                            id = R.string.models_update_available
                                        ),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colors.error
                                    )
                                }
                            }
                            IconButton(onClick = {
                                val clipboard = activity.getSystemService(
                                    Context.CLIPBOARD_SERVICE
                                ) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("model-url", ml.link)
                                )
                                Toast.makeText(
                                    activity,
                                    activity.getString(R.string.models_url_copied),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(
                                        id = R.string.models_copy_url
                                    )
                                )
                            }
                        }
                    }
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(0.dp, (LocalConfiguration.current.screenHeightDp * 0.9).dp)
                ) {
                    val voskEntries = available.filter { it.engine == null }
                    val whisperEntries = available.filter {
                        it.engine == SherpaEngine.WHISPER
                    }
                    val parakeetEntries = available.filter {
                        it.engine == SherpaEngine.PARAKEET && !it.streaming
                    }
                    val parakeetLiveEntries = available.filter {
                        it.engine == SherpaEngine.PARAKEET && it.streaming
                    }
                    if (voskEntries.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(id = R.string.models_download_section_vosk),
                                fontSize = 14.sp
                            )
                        }
                        items(items = voskEntries) { ml -> card(ml) }
                    }
                    if (whisperEntries.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(id = R.string.models_download_section_whisper),
                                fontSize = 14.sp
                            )
                        }
                        items(items = whisperEntries) { ml -> card(ml) }
                    }
                    if (parakeetEntries.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(id = R.string.models_download_section_parakeet),
                                fontSize = 14.sp
                            )
                        }
                        items(items = parakeetEntries) { ml -> card(ml) }
                    }
                    if (parakeetLiveEntries.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(id = R.string.models_download_section_parakeet_live),
                                fontSize = 14.sp
                            )
                        }
                        items(items = parakeetLiveEntries) { ml -> card(ml) }
                    }
                }
            }
        }

        if (showImportDialog) {
            AlertDialog(onDismissRequest = {

            }, confirmButton = {
                Button(onClick = {
                    showImportDialog = false
                    activity.importModel()
                }) {
                    Text(text = stringResource(id = R.string.models_import_dialog_import))
                }
            }, dismissButton = {
                Button(onClick = { showImportDialog = false }) {
                    Text(text = stringResource(id = R.string.button_cancel))
                }
            }, title = {
                Text(text = stringResource(id = R.string.models_import_dialog_title))
            }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val annotatedString = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = MaterialTheme.colors.onSurface)) {
                        append(stringResource(id = R.string.models_import_dialog_text_before_link))
                    }

                    pushStringAnnotation(
                        tag = "vosk-website", annotation = "https://alphacephei.com/vosk/models"
                    )
                    withStyle(style = SpanStyle(color = MaterialTheme.colors.primary)) {
                        append(stringResource(id = R.string.models_import_dialog_text_link))
                    }
                    pop()

                    withStyle(style = SpanStyle(color = MaterialTheme.colors.onSurface)) {
                        append(stringResource(id = R.string.models_import_dialog_text_after_link))
                    }
                }

                ClickableText(text = annotatedString,
                    style = MaterialTheme.typography.body1,
                    onClick = { offset ->
                        annotatedString.getStringAnnotations(
                            tag = "vosk-website", start = offset, end = offset
                        ).firstOrNull()?.let {
                            // U18: external view goes through the chooser.
                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(it.item))
                            activity.startActivity(
                                Intent.createChooser(browserIntent, it.item.take(200))
                            )
                        }
                    })
                Text(
                    text = stringResource(id = R.string.models_import_dialog_text_sherpa),
                    style = MaterialTheme.typography.body1
                )
                Button(onClick = {
                    showImportDialog = false
                    activity.importModelFolder()
                }) {
                    Text(text = stringResource(id = R.string.models_import_dialog_folder))
                }
                }
            })
        }
    }

    /**
     * Compare installed catalog models against upstream ETag/Last-Modified
     * validators. Only models with a recorded download can be compared;
     * others are skipped silently. Runs off the main thread.
     */
    private fun checkForUpdates() {
        // D16: offline mode blocks the update-check HEAD round-trips.
        // Defensive default-off read (same gate as the service layer).
        if (isOfflineMode()) {
            Toast.makeText(
                activity,
                activity.getString(R.string.logic_offline_blocked),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (checkingUpdatesLD.value == true) return
        checkingUpdatesLD.postValue(true)
        Thread {
            try {
                val installed = prefs.modelsOrder.get()
                val found = mutableSetOf<String>()
                for (ref in installed) {
                    val leaf = ref.path.substringAfterLast('/')
                    val entry = ModelLink.values().firstOrNull {
                        it.archiveBaseName == leaf
                    } ?: continue
                    val record = prefs.downloadHashes.get()[entry.link] ?: continue
                    // Always three parts (bytes:hash:validator, validator may
                    // be empty): older two-part records normalize on first
                    // sighting instead of silently re-baselining every check.
                    val parts = record.split(":", limit = 3)
                    if (parts.size < 2) continue
                    val normalized =
                        "${parts[0]}:${parts[1]}:${parts.getOrNull(2).orEmpty()}"
                    if (normalized != record) {
                        prefs.downloadHashes.set(
                            prefs.downloadHashes.get() + (entry.link to normalized)
                        )
                    }
                    val recordedValidator = parts.getOrNull(2)
                    val current = headValidator(entry.link) ?: continue
                    if (recordedValidator.isNullOrEmpty()) {
                        // First sighting: record silently, no badge.
                        prefs.downloadHashes.set(
                            prefs.downloadHashes.get() +
                                (entry.link to "${parts[0]}:${parts[1]}:$current")
                        )
                    } else if (current != recordedValidator) {
                        found.add(entry.archiveBaseName)
                    }
                }
                updatesAvailableLD.postValue(found)
                // D19: persisted log keeps the count; leaf detail is DEBUG only.
                AppLog.d(TAG, "update check done count=${found.size}")
                if (BuildConfig.DEBUG) Log.d(TAG, "update check updates=$found")
            } catch (e: Exception) {
                AppLog.e(TAG, "update check failed", e)
            } finally {
                checkingUpdatesLD.postValue(false)
            }
        }.start()
    }

    /**
     * D16: fail-closed HEAD validator. Returns null (skip, no badge) when
     * offline mode is on, when the link fails the [Sanitize] https +
     * host allow-list gate, or on any I/O surprise. Redirects are followed
     * manually (max [Sanitize.MAX_REDIRECTS] hops) with every hop
     * re-validated — never the platform auto-follow, which could hop to a
     * non-allow-listed host. Timeouts mirror [Sanitize].
     */
    private fun headValidator(link: String): String? {
        if (isOfflineMode()) return null
        var current = try {
            Sanitize.sanitizeDownloadUrl(link)
        } catch (_: Exception) {
            return null
        }
        var conn: java.net.HttpURLConnection? = null
        try {
            // Bounded hop loop (initial + MAX_REDIRECTS redirects); every hop
            // is re-validated, so a redirect can never leave the allow-list.
            repeat(Sanitize.MAX_REDIRECTS + 1) {
                try {
                    Sanitize.sanitizeDownloadUrl(current.toString())
                } catch (_: Exception) {
                    return null
                }
                val opened = try {
                    current.openConnection()
                } catch (_: Exception) {
                    return null
                }
                conn = opened as? java.net.HttpURLConnection ?: return null
                val c = conn
                if (c == null) return null
                c.instanceFollowRedirects = false
                c.requestMethod = "HEAD"
                c.connectTimeout = Sanitize.CONNECT_TIMEOUT_MS
                c.readTimeout = Sanitize.READ_TIMEOUT_MS
                c.connect()
                val code = try {
                    c.responseCode
                } catch (_: Exception) {
                    return null
                }
                if (code == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                    code == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == java.net.HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 || code == 308
                ) {
                    val location = c.getHeaderField("Location")
                    try {
                        c.disconnect()
                    } catch (_: Exception) {
                    }
                    conn = null
                    if (location == null) return null
                    try {
                        current = java.net.URL(current, location)
                    } catch (_: Exception) {
                        return null
                    }
                    return@repeat
                }
                if (code != java.net.HttpURLConnection.HTTP_OK) return null
                return c.getHeaderField("ETag") ?: c.getHeaderField("Last-Modified")
            }
            // Hop budget exhausted following redirects: skip, no badge.
            return null
        } catch (_: Exception) {
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** D16: offline pref read defensively — failure means default-off. */
    private fun isOfflineMode(): Boolean {
        return try {
            prefs.logicOfflineMode.get()
        } catch (_: Exception) {
            false
        }
    }

    private fun updateCurrentDownloading(info: ModelInfo): DownloadModelProgress {
        var currentModel = currentDownloadingModel.value

        if (currentModel == null) {
            currentModel = DownloadModelProgress(
                info, State.NONE, 0f
            )
        } else if (currentModel.info != info) {
            val newCurrent = modelsPendingDownload.find { it == currentModel!!.info }
            if (newCurrent == null) {
                currentModel = DownloadModelProgress(
                    info, State.NONE, 0f
                )
            } else {
                modelsPendingDownload.remove(newCurrent)
                modelsPendingDownloadLD.postValue(modelsPendingDownload.toList())
                currentModel = DownloadModelProgress(
                    newCurrent, State.NONE, 0f
                )
            }
        }
        currentDownloadingModel.postValue(currentModel)
        return currentModel
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onAddToDownload(info: ModelInfo) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onAddToDownload")

        if (modelsPendingDownload.contains(info)) return
        modelsPendingDownload.add(info)
        modelsPendingDownloadLD.postValue(modelsPendingDownload.toList())
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onState(state: DownloadState) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onState: ${state.state}")
        when (state.state) {
            State.QUEUED -> onAddToDownload(state.info)
            State.DOWNLOAD_STARTED, State.NONE -> {
                modelsPendingDownload.remove(state.info)
                modelsPendingDownloadLD.postValue(modelsPendingDownload.toList())
                currentDownloadingModel.postValue(
                    updateCurrentDownloading(state.info).withState(
                        state.state
                    )
                )
            }

            State.FINISHED -> {
                currentDownloadingModel.postValue(null)
                // Installed set changed: badges re-evaluate on next check.
                updatesAvailableLD.postValue(emptySet())
                reloadModels()
            }

            else -> {
                currentDownloadingModel.postValue(
                    updateCurrentDownloading(state.info).withState(
                        state.state
                    )
                )
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onStatus(status: Status) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onStatus: ${status.state}")
        if (status.current != null) {
            onState(DownloadState(status.current, status.state))
            when (status.state) {
                State.DOWNLOAD_STARTED -> onDownloadProgress(
                    DownloadProgress(
                        status.current, status.downloadProgress
                    )
                )

                State.UNZIP_STARTED -> onUnzipProgress(
                    UnzipProgress(
                        status.current, status.unzipProgress
                    )
                )

                else -> {}
            }
        } else {
            currentDownloadingModel.postValue(null)
        }
        modelsPendingDownload.clear()
        for (modelInfo in status.queued) {
            onState(DownloadState(modelInfo, State.QUEUED))
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onDownloadProgress(progress: DownloadProgress) {
//        Log.d(TAG, "onDownloadProgress($progress)")
        // CG wasteful, but works
        currentDownloadingModel.postValue(
            updateCurrentDownloading(progress.info).withProgress(
                progress.progress
            )
        )
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onUnzipProgress(progress: UnzipProgress) {
//        Log.d(TAG, "onUnzipProgress($progress)")
        currentDownloadingModel.postValue(
            updateCurrentDownloading(progress.info).withProgress(
                progress.progress
            )
        )
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onCancelFinished(event: CancelFinished) {
        if (currentDownloadingModel.value?.info == event.info) {
            currentDownloadingModel.postValue(null)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onDownloadError(error: DownloadError) {
        // U18: bound toast length (≤200 chars).
        val msg = (error.message + "\n" + activity.getString(R.string.models_see_log)).take(200)
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }

    fun onStart() {
        EventBus.getDefault().register(this)
        EventBus.getDefault().post(StatusQuery())
    }

    fun onStop() {
        EventBus.getDefault().unregister(this)
    }

    fun onResume() {
        reloadModels()
        EventBus.getDefault().post(StatusQuery())
    }

    /**
     * D2-badge: per-installed-model hash badge from the downloadHashes
     * record — a validator triple (bytes:hash:validator) means re-verified,
     * a bare pair is trust-on-first-use. Null when no record exists.
     */
    @Composable
    private fun hashBadge(item: InstalledModelReference): String? {
        val hashes by prefs.downloadHashes.observeAsState()
        val leaf = item.path.substringAfterLast('/')
        val entry = ModelLink.values().firstOrNull { it.archiveBaseName == leaf }
            ?: return null
        val record = hashes[entry.link] ?: return null
        val parts = record.split(":", limit = 3)
        if (parts.size < 2) return null
        return if (parts.getOrNull(2).isNullOrEmpty()) {
            stringResource(id = R.string.models_hash_unverified)
        } else {
            stringResource(id = R.string.models_hash_verified)
        }
    }

    companion object {
        private const val TAG = "ModelsSettingUi"

        /** U14: alias cap (32) + strip \n\r/bidi/controls. */
        private val ALIAS_BIDI = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069\u061C\uFEFF]")
        fun sanitizeAlias(raw: String): String {
            var out = ALIAS_BIDI.replace(raw.trim(), "")
            out = out.filter { c -> c != '\n' && c != '\r' && c.code >= 0x20 && c != '\u007F' }
            if (out.length > 32) out = out.take(32)
            return out
        }
    }
}