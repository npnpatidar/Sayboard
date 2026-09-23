# Sayboard — Security & Necessity Audit (simplified, audit complete)

**Date:** 2026-09-23 (simplified after completion)
**Repo:** `/root/Sayboard` (Sayboard v4.2.1)
**Scope:** Full — every Kotlin file (66), `AndroidManifest.xml`, Gradle build, CI, resources, locales, fastlane, docs.
**Threat model (standard offline keyboard):** (1) Verify no exfiltration of audio/transcripts, (2) tolerate malicious model/archive/file/folder/backup, (3) rogue app abusing `IME` / `RecognitionService` / clipboard / IME-switch.
**Method per file/block (pre-fix):** `Purpose → implication → Necessity verdict [KEEP / CONDITIONAL / REMOVE / SIMPLIFY] → findings with severity + remediation.`
**Severity:** `Critical > High > Medium > Low > Info`.

> **Simplification note:** all fixed findings on which auditor and coder agreed — with no intentional design deviation — have been removed, along with the pre-squash commit history and intermediate verification (§§12–14.8). This file is the complete record going forward: (a) intentional design decisions, (b) yet-to-fix scheduled follow-ups, (c) final closure.

---

## 0. Executive summary (final)

No intentional exfiltration in IME/recognition/service paths. Risk was capability + supply-chain + file-parse + log/clipboard handling. All agreed fixes are landed and verified through final close (F2-notice). Remaining work is §2 only, plus the intentional behaviors in §1 which are kept by decision, not by omission.

---

## 1. Intentional design decisions (kept, not reverted)

- **F1 — Unconditional result commit (Accepted Risk).** Results commit in every app; `dropReason()` (`IME.kt`) is diagnostics-only (reason codes, PII-clean). Re-opens the original I-08/I-02 shape (late final after a fast switch can land in the focused app). Kept because the session gate caused observed silent dictation loss (browsers, banking apps, Termux-like terminals bouncing the input view), while misdirection needs a narrow callback-window race and is visible/user-correctable. Wrong-but-visible beats silent loss for a keyboard.
- **F2 — Plain-editor fallback + partials policy.** `TextManager.commitTextOrKeys` routes `TYPE_NULL` editors via key events; null-mapping arm posts user-visible `ime_no_key_mapping` through `IME.showEditorNotice()` (`IME.kt:110-116`: `errorMessageLD` + `STATE_ERROR`, mirroring `refuseVoiceInPassword()`) and still attempts `commitText`. Visibility holds via `ViewManager.kt:921-929` (error arm). Partials stay suppressed without notice (streaming partials would spam). Known script limitation: unmappable scripts in plain editors remain best-effort.
- **F3 — Keep-resident + per-model pins (soft guard done, full cap open).** Loaded sources are reused; pins default resident with per-row toggle. `Tools.residentFitsRam()` (candidate + pinned sum vs `availMem/2`) soft-refuses pinning an Nth large model; summary documents precedence (global-OFF frees all; pins apply when global is ON) and per-model RAM expectations. Full LRU/total-cap is scheduled (§2), not smuggled into hardening.
- **F5 — Vosk zero-byte tolerance (compat).** Zero-length files skip preflight because upstream ships legitimate 0-byte configs (e.g. Hindi `ivector/online_cmvn.conf`). Bomb protection rests on entry-count + `VOSK_MAX_BYTES` + R3 memory gate; a zeroed `final.mdl` fails deterministically at native load as ERROR (fail-closed).
- **F6/F7 — Redaction/heuristic limits (stated, accepted).** Log exemption is exact names (`final.mdl`, `Gr.fst`, `tokens.txt`, case-insensitive) + `.fst`/`.mdl`/`.onnx`/`.onnxt` only; everything else redacts (known cost: `phones.txt`/`mfcc.conf` redact). ONNX/Kaldi gating is a documented heuristic (first-byte tag + printable-fraction; `TransitionModel`/`Nnet`), not a full parse — blocks text-decoy/`<>`-junk, crafted dense binaries could still pass to fail-closed native load. `redactErrorMessage` keeps load-phase `msg=` under an extended allowlist with the limit stated in KDoc.
- **F9 — Fallback/blacklist fail-closed.** `loadFallbackTried` clears on successful reload (rebind can resurrect a blacklisted index, then it fails deterministically — bounded, visible ERROR, no loop); all-blacklisted stays on the broken model with ERROR (manual switch recovers). Priority is never silently jumping models under the user. Main-thread confinement documented at declaration.
- **F10 — Posture flags never weaken via import.** `b_allow_external_recognition`, `b_offline_mode`, `b_restore_model_paths`, `b_allow_voice_in_password` refuse weakening restores (strengthening/no-op apply); refusals are named in the restore report + `AppLog` line.
- **F11 — Password-gate notes (intentional).** `checkAddSpaceAndCapitalize` uses strict `isPasswordField()` ignoring the voice opt-in (opt-in users get commits but no auto-spacing there — privacy-leaning, commented). `STATE_ERROR` notice self-heals on next recognizer state (brief stale message possible, cosmetic). Type-hex/mode-only logs, PII-clean.
- **Password posture (implemented).** Live `isPasswordField()` + `isVoiceBlockedByPassword()` gates in all `IME` callbacks + `TextManager` defense-in-depth; blocks post on-keyboard error + best-effort toast; opt-in `logicAllowVoiceInPassword` (`b_allow_voice_in_password`, default off, Settings → Logic, backed up/restored).
- **Conditional keeps (implemented posture).** KeepAlive + boot receiver opt-in default-off with disclosure; `SayboardRecognitionService` behind default-off kill-switch + rate-limit + attribution + indicator; downloader + `INTERNET` behind https/host allow-list + hashes + offline toggle; `QUERY_ALL_PACKAGES` replaced by `<queries>`; logs app-private + redacted + opt-out with SAF export; copy requires non-empty selection with password skip.

---

## 2. Yet to fix — scheduled follow-ups (only open items)

- **F3-full — Resident total-cap / LRU.** Soft refuse-to-pin ships; full eviction under memory pressure needs its own design + device testing. Minor hygiene with it: per-tap guard thread, installed-list vs loaded-refs divergence (errs toward refusing = safe), manager wrapper retained for IME callers.
- **B10 — targetSdk 36/37.** Needs device testing; CI-only verification insufficient (risky with JNA/Vosk).
- **B21 — Jetifier/nonFinal/nonTransitive flips.** Same device-test reason; Jetifier stays on for JNA/Vosk.
- **B14 — EventBus → StateFlow/callbacks.** Sequenced after P1; bus currently restricted to download/model events, never audio/text.
- **B15 — JetPref beta → stable DataStore.** Sequenced after P1; pin + validate under R8 in place.
- **B27-part — SLSA / provenance attestation.** Release + lint + PR-trigger smoke ships; attestation still open.
- **S20 — Translations.** Newest strings English-only; push to Weblate/Crowdin (a11y/consent/keep-alive/crash/backup first).
- **S22 — RTL QA.** Partial ar/fa coverage; needs mirroring QA.
- **B18/B31 — Release-license proof.** Disclosure + AboutLibraries wiring done; transitive surfacing still unverified on a signed release build.

---

## 3. Final closure

F2-notice verified closed: `showEditorNotice()` posts message + error state together with corrected KDoc; transient rationale holds (next recognizer state overwrites both, same as mic-permission/recognizer-error/password-block paths). All §§14.5/14.7 authorized items closed; F1/F5 remain closed Accepted Risk/compat. No open items beyond §2.

*End of audit (simplified). Full history in git; remediation verified through final close; audit complete.*
