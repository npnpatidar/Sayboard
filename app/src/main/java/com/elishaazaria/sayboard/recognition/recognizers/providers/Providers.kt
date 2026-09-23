package com.elishaazaria.sayboard.recognition.recognizers.providers

import android.content.Context
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource

/**
 * Router over the installed-model providers.
 *
 * Holds only the application context (R8): providers outlive any single
 * IME/service instance and must never retain an Activity or attribution
 * context.
 */
class Providers(context: Context) {
    // Lazy: Providers is constructed as a field initializer (e.g.
    // ModelsSettingsUi created in SettingsActivity.<init>), where the
    // Activity has no base context yet and applicationContext would NPE.
    // First use always happens post-attach (onCreate / IME bind), so
    // deferring resolution here is safe and still never retains the Activity.
    private val appContext: Context by lazy { context.applicationContext }
    private val voskLocalProvider: VoskLocalProvider by lazy { VoskLocalProvider(appContext) }
    private val sherpaProvider: SherpaProvider by lazy { SherpaProvider(appContext) }
    private val providers: List<RecognizerSourceProvider> by lazy {
        listOf(voskLocalProvider, sherpaProvider)
    }

    fun recognizerSourceForModel(localModel: InstalledModelReference): RecognizerSource? {
        return when (localModel.type) {
            ModelType.VoskLocal -> voskLocalProvider.recognizerSourceForModel(localModel)
            ModelType.WhisperLocal, ModelType.ParakeetLocal, ModelType.StreamingLocal ->
                sherpaProvider.recognizerSourceForModel(localModel)
            ModelType.UNKNOWN -> null
        }
    }

    fun installedModels(): Collection<InstalledModelReference> {
        return providers.map { it.getInstalledModels() }.flatten()
    }
}