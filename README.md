<img align="left" width="80" height="80"
src="app/src/main/ic_launcher-playstore.png" alt="App icon">
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">](https://f-droid.org/en/packages/com.elishaazaria.sayboard/)


# Sayboard

Sayboard is an open-source, on-device voice keyboard (IME) for Android.
Speech is transcribed locally on the phone with one of four offline
engines — **Vosk** (live transcription), **Whisper** and **Parakeet**
(record, then transcribe), and **Streaming** checkpoints (live
transcription) — powered by Vosk and sherpa-onnx. It is based on
[https://github.com/Felicis/vosk-android-demo](https://github.com/Felicis/vosk-android-demo).

On-device transcription is the default and the only mode this build
ships: there is no remote-server option. Nothing you dictate is ever
sent anywhere by Sayboard itself; `INTERNET` is used only for model
downloads, update checks, and manual imports (see below).

You need at least one speech model to use the keyboard. Open the Models
tab to download from the built-in catalog (every entry carries a
SHA-256 hash that is verified before install, with the verification
state shown per model), or import manually: Vosk models from
[https://alphacephei.com/vosk/models](https://alphacephei.com/vosk/models),
Whisper/Parakeet files (`encoder.onnx`, `decoder.onnx`, `tokens.txt`)
from a sherpa-onnx Hugging Face repo, either as archives or as an
extracted folder. Each model ships under its own data license — check
the model page before downloading.

## Speech engines and model sizes

| Engine | Mode | Models (built-in catalog) |
|---|---|---|
| Vosk | live transcription | 21 small per-language models (en-US, en-IN, zh, ru, fr, de, es, pt, tr, vi, it, nl, ca, fa, kk, ja, eo, hi, cs, pl, uk), tens of MB each |
| Whisper (sherpa-onnx, int8) | record, then transcribe | tiny ~120 MB, base ~200 MB, small ~610 MB, turbo ~540 MB (multilingual, language auto-detected) |
| Parakeet TDT (sherpa-onnx, int8) | record, then transcribe | 110M-en ~103 MB, 0.6B-v2-en ~460 MB, 0.6B-v3-EU ~465 MB (25 European languages) |
| Streaming (sherpa-onnx) | live transcription | zipformer-en ~296 MB (real-time capable), parakeet-unified-en ~480 MB (needs a powerful phone) |

Voice activity detection uses the bundled Silero VAD model
(`silero_vad.onnx`, sha-pinned in the app assets; Silero, MIT license)
for the optional stop-on-silence feature. All third-party libraries and
their licenses are listed in-app under Logic settings → Show Used
Libraries (AboutLibraries).

## Permissions (canonical list, mirrors the manifest)

- `RECORD_AUDIO` — the microphone for voice input. Also gates the
  optional external `RecognitionService`: other apps can use Sayboard
  voice input only if you enable "Allow other apps to use Sayboard
  voice input" (off by default, mic indicator shown while serving,
  rate-limited).
- `INTERNET` — install-time normal permission. Needed only for model
  downloads and update checks; on-device transcription needs none. If
  your ROM offers a per-app internet toggle, you can revoke it after
  installing your models — or turn on Offline mode in Logic settings
  to block all downloads and update checks in-app.
- `POST_NOTIFICATIONS` — download/import progress (asked at runtime
  on Android 13+).
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` — model
  download/import service in the background.
- `FOREGROUND_SERVICE_MICROPHONE` — declared for the recognition
  service.
- `FOREGROUND_SERVICE_SPECIAL_USE` — keep-alive service.
- `RECEIVE_BOOT_COMPLETED` — re-establishes the opt-in keep-alive
  after a reboot, and only if keep-alive is enabled.

## Typing, terminals, and password fields

- Sayboard works in every text field, including terminal emulators
  and other plain (`TYPE_NULL`) editors: where such apps ignore rich
  commit calls, keys, voice results, and backspace are delivered as
  raw key events instead.
- Voice input is disabled in password-typed fields by default, with
  an on-keyboard notice. Some apps (including terminals) mark normal
  fields as visible-password to suppress autocorrect — if dictation
  must work everywhere, enable "Allow voice input in password
  fields" in Logic settings (off by default).
- Copy requires a non-empty selection and never touches password
  fields; pasting caps at 8,000 characters with control characters
  stripped (large pastes ask first).

## Memory, keep-alive, and logs

- Keep model in RAM (default on): instant reopen at the cost of
  hundreds of MB resident per model. Turning it off frees every
  model when the keyboard closes; while on, per-model pin toggles
  (Models tab) choose which models stay resident, and pinning a
  further large model is refused when free RAM is short.
- Keep-alive ("Keep alive in background") is off by default. When
  on, a permanent notification keeps the loaded model in memory so
  switching keyboards stays instant; it uses RAM, never touches the
  microphone, and offers a one-tap Turn-off action. The boot
  receiver only restarts it if it was on — otherwise nothing runs
  at boot.
- Diagnostic logs live in the app-private `filesDir/SayboardLogs`
  directory (never in shared storage), rotated to the last 5
  sessions, and can be shared from inside the app via Export log
  (SAF picker). Lines are redacted before persisting (URLs, absolute
  paths reduced to file names, package-like tokens, no transcripts);
  logcat mirroring is debug-builds only. File logging can be turned
  off under Logic settings → Write diagnostic log file.
- Crash reports show a warning that the report may contain dictated
  text — review before sharing.

## Backup and restore

Backups are plain JSON files you read and write through the system
file picker (capped at 2 MB on import; one bad value never aborts
the rest). Restoring never weakens security posture: enabling
external recognition, voice in password fields, model-path restores,
or turning offline mode off is refused when it would weaken your
current settings, and refusals are named in the restore report.
Installed-model paths are excluded unless explicitly opted in.

## Network endpoints

Downloading a model or checking for updates contacts these hosts
(transcription itself is fully offline):

- `alphacephei.com` — Vosk model catalog and downloads.
- `github.com` (`k2-fsa/sherpa-onnx` releases) — Whisper/Parakeet/
  streaming archives plus per-model update checks.
- Hugging Face sherpa-onnx repos — only if you manually import
  those files.

## Language and settings notes

- The IME declares a single locale-agnostic voice subtype, so the OS
  cannot auto-select it per language. Instead, Sayboard is
  model-driven: "Match model to field language" follows the text
  field's locale when the app reports one, and the opt-in "Remember
  language per app" reapplies the last language per app (off by
  default, clearable).
- One Settings entry serves both the keyboard and the recognition
  service.
- Appearance (theme mode, dynamic colors, key borders, accent and
  panel colors), keyboard height, and a customizable key row live
  under UI and Keyboard settings.
- Newest strings ship in English until translators catch up
  (English is the fallback); right-to-left mirroring QA is still
  pending — reports welcome.

## Performance tunables

- Stop recording on silence (default off): ends Whisper/Parakeet
  takes after ~1 s of trailing silence via the Silero VAD gate.
- Engine threads/CPU: Whisper/Parakeet use 1–4 threads by CPU
  count, streaming uses a single thread; all run on the `cpu`
  provider with debug off.
- Take caps: 120 s non-streaming / 300 s streaming, 15 s
  pre-speech timeout, 10 MB audio buffer.

## Support

Android 6.0 (API 23) and newer. Runtime permission behavior varies
by version (e.g. the notification prompt on Android 13+); please
test on both old and new releases and report issues at
[https://github.com/npnpatidar/Sayboard](https://github.com/npnpatidar/Sayboard).
