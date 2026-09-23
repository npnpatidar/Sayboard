package com.elishaazaria.sayboard.data

import com.elishaazaria.sayboard.Constants
import java.util.*

// Locale list available at: https://stackoverflow.com/questions/7973023/what-is-the-list-of-supported-languages-locales-on-android
/**
 *
 */
enum class ModelLink(
    val link: String,
    val locale: Locale,
    /** Null for Vosk models; set for sherpa-onnx (Whisper/Parakeet) models. */
    val engine: SherpaEngine? = null,
    /** Approximate download size shown in the UI, e.g. "~660 MB". */
    val sizeHint: String = "",
    /** True for streaming (live transcription) checkpoints. */
    val streaming: Boolean = false,
    /**
     * SHA-256 of the archive, enforced on every download (not just against
     * our own past record), so a corrupt first download can never become
     * the baseline.
     *
     * Provenance: measured 2026-09-23 by downloading each catalog URL and
     * hashing the exact bytes served (see /tmp/hashes.tsv method). These are
     * NOT publisher-published checksums — neither upstream publishes any —
     * so if an upstream re-rolls a file, downloads will fail closed with a
     * hash-mismatch error until the entry is re-measured. Null = TOFU pending
     * measurement: integrity falls back to the self-recorded downloadHashes
     * entry (first-seen bytes+hash, fail-closed on later mismatch). Hashes
     * must never be invented here.
     */
    val expectedSha256: String? = null,
    /**
     * Exact byte size of the archive, when known (same provenance policy as
     * [expectedSha256]). Null = TOFU pending measurement.
     */
    val expectedBytes: Long? = null
) {
    ENGLISH_US(
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        Locale.US,
        expectedSha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
        expectedBytes = 41205931
    ),
    ENGLISH_IN(
        "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
        Locale("en", "IN"),
        expectedSha256 = "20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7",
        expectedBytes = 37573330
    ),
    CHINESE(
        "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
        Locale.CHINESE,
        expectedSha256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba",
        expectedBytes = 43898754
    ),
    RUSSIAN(
        "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
        Locale("ru"),
        expectedSha256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4",
        expectedBytes = 46236750
    ),
    FRENCH(
        "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
        Locale.FRENCH,
        expectedSha256 = "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad",
        expectedBytes = 42233323
    ),
    GERMAN(
        "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip",
        Locale.GERMAN,
        expectedSha256 = "b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43",
        expectedBytes = 46499967
    ),
    SPANISH(
        "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip",
        Locale("es"),
        expectedSha256 = "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f",
        expectedBytes = 39817833
    ),
    PORTUGUESE(
        "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip",
        Locale("pt"),
        expectedSha256 = "6e1ce909032e1afa7a88e68a3d628ecafff302bdf195befab308826c395e93b7",
        expectedBytes = 32453112
    ),
    TURKISH(
        "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip",
        Locale("tr"),
        expectedSha256 = "8c8d07cec1bce31add14967c16891c84152cdf76d391e33c08278119b9ea96e5",
        expectedBytes = 36855784
    ),
    VIETNAMESE(
        "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.3.zip",
        Locale("vi"),
        expectedSha256 = "a97397742c77707a8850ea6fc74119cff583bb58206460d1b83aa8ca274ef440",
        expectedBytes = 33655048
    ),
    ITALIAN(
        "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip",
        Locale.ITALIAN,
        expectedSha256 = "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267",
        expectedBytes = 49665141
    ),
    DUTCH(
        "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip",
        Locale("nl"),
        expectedSha256 = "039811c3b829de64e4f123a9f684a53784005b212a346ac0b899dc7efce2ed0a",
        expectedBytes = 40441176
    ),
    CATALAN(
        "https://alphacephei.com/vosk/models/vosk-model-small-ca-0.4.zip",
        Locale("ca"),
        expectedSha256 = "99f90bfff5c2b187c705ddbc20aaa2600eddc359466f404404f1db6029fba5d4",
        expectedBytes = 43405881
    ),
    PERSIAN(
        "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.4.zip",
        Locale("fa"),
        expectedSha256 = "8af0530e232a3c3424bd6b7a582d434b8bde3f25e33541a8a250cb04e59ab01c",
        expectedBytes = 48743434
    ),
    KAZAKH(
        "https://alphacephei.com/vosk/models/vosk-model-small-kz-0.15.zip",
        Locale("kk"),
        expectedSha256 = "b5eb54b400d1b66d1b017cadfa63725cec7359cb7d54528f6326d02689984c49",
        expectedBytes = 43739114
    ),
    JAPANESE(
        "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
        Locale.JAPANESE,
        expectedSha256 = "efa092d280153a77615e9e0c7d7283e93e600de3d19d3bec686c57ef19d52eac",
        expectedBytes = 49704573
    ),
    ESPERANTO(
        "https://alphacephei.com/vosk/models/vosk-model-small-eo-0.42.zip",
        Locale("eo"),
        expectedSha256 = "4459ca908f7eae862a5975305f2a720735f5205a9884d8f559dc46fe70034636",
        expectedBytes = 43839401
    ),
    HINDI(
        "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
        Locale("hi"),
        expectedSha256 = "7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48",
        expectedBytes = 44458845
    ),
    CZECH(
        "https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip",
        Locale("cs"),
        expectedSha256 = "287c3bbefc8ad67b4ab9636eecef3d62acc3719990777d03e226db5a7f19fbda",
        expectedBytes = 46088666
    ),
    POLISH(
        "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip",
        Locale("pl"),
        expectedSha256 = "c4cd16498ea544f446f9e9a55cbd602b71cfe5a2b6f2b0834d81e1b6fce15f0d",
        expectedBytes = 52979372
    ),
    UKRAINIAN(
        "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-small.zip",
        Locale("uk"),
        expectedSha256 = "ee05b6a8c796c53c61cd1c821d38e897e5bd67b4d691d8797ec8f9e5100d4247",
        expectedBytes = 143914407
    ),
    // sherpa-onnx Whisper models (multilingual, int8). Shipped as .tar.bz2.
    // Language is auto-detected at decode time, hence the undefined locale.
    WHISPER_TINY(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
        Constants.UndefinedLocale,
        SherpaEngine.WHISPER,
        "~120 MB",
        expectedSha256 = "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
        expectedBytes = 116204861
    ),
    WHISPER_BASE(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
        Constants.UndefinedLocale,
        SherpaEngine.WHISPER,
        "~200 MB",
        expectedSha256 = "911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de",
        expectedBytes = 207557382
    ),
    WHISPER_SMALL(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
        Constants.UndefinedLocale,
        SherpaEngine.WHISPER,
        "~610 MB",
        expectedSha256 = "486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6",
        expectedBytes = 639387718
    ),
    WHISPER_TURBO(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-turbo.tar.bz2",
        Constants.UndefinedLocale,
        SherpaEngine.WHISPER,
        "~540 MB",
        expectedSha256 = "b11acbbcd660b44a8e0df33724feb5aaa709cf65668f2823d59f656312544f22",
        expectedBytes = 563790207
    ),
    // sherpa-onnx Parakeet TDT models (record, then transcribe).
    // v2 is English-only; v3 covers 25 European languages.
    PARAKEET_V2_EN(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
        Locale.ENGLISH,
        SherpaEngine.PARAKEET,
        "~460 MB",
        expectedSha256 = "157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad",
        expectedBytes = 482468385
    ),
    PARAKEET_V3_EU(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        Constants.UndefinedLocale,
        SherpaEngine.PARAKEET,
        "~465 MB",
        expectedSha256 = "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf",
        expectedBytes = 487170055
    ),
    // Parakeet 110M transducer (English): offline record-then-transcribe,
    // much faster than the 0.6B models. Verified on-device path.
    PARAKEET_110M_EN(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2",
        Locale.ENGLISH,
        SherpaEngine.PARAKEET,
        "~103 MB",
        expectedSha256 = "f628312e9fdf8686374cb01a69425c41732529d540860311f16f37cbc32cfe9b",
        expectedBytes = 108035095
    ),
    // Streaming checkpoints for live transcription (verified working):
    // zipformer English, phone-sized and real-time capable.
    ZIPFORMER_STREAMING_EN(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
        Locale.ENGLISH,
        SherpaEngine.PARAKEET,
        "~296 MB",
        true,
        expectedSha256 = "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282",
        expectedBytes = 310414022
    ),
    // Parakeet unified streaming (English, live): best live quality, but
    // needs a powerful phone (too slow for real-time on mid-range SoCs).
    PARAKEET_STREAMING_EN(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-560ms.tar.bz2",
        Locale.ENGLISH,
        SherpaEngine.PARAKEET,
        "~480 MB",
        true,
        expectedSha256 = "dd2c2698f102eafbf0ee54bdfd7cd842ec00fa6cf2475cbbb048887f794ff52e",
        expectedBytes = 501360769
    );

    /** Full archive file name, e.g. "vosk-model-small-en-us-0.15.zip". */
    val filename: String
        get() = link.substring(link.lastIndexOf('/') + 1)

    /**
     * Archive base name without a known archive extension, used to match a
     * catalog entry against an installed model directory, e.g.
     * "sherpa-onnx-whisper-tiny".
     */
    val archiveBaseName: String
        get() {
            var name = filename
            for (ext in listOf(".tar.bz2", ".tbz2", ".tgz", ".zip", ".tar.gz")) {
                if (name.endsWith(ext, ignoreCase = true)) {
                    name = name.dropLast(ext.length)
                    break
                }
            }
            return name
        }
}