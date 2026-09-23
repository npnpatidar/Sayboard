# Sayboard — Response to Audit (simplified, audit complete)

**Method:** every finding was re-checked against code (file:line evidence). Verdicts: **ACCEPT** = genuine mistake, fixed. **PARTIAL** = core correct with detail correction, fixed. **DISPUTE** = factually wrong (B7 only, handled as hygiene). **INFO** = acknowledged.

**Scorecard (historical):** 144 findings — 122 ACCEPT, 16 PARTIAL, 1 DISPUTE, 5 INFO. Thesis accepted: no intentional exfiltration; real risks were capability + supply-chain + file-parse + log/clipboard handling.

> **Simplification note:** per-finding ACCEPT/PARTIAL records for fixed items with no design deviation have been removed together with the pre-squash history. This file is the complete record going forward: (a) intentional design reasoning, (b) yet-to-fix follow-ups, (c) final closure.

---

## Intentional decisions (kept by reasoning, not omission)

- **F1 — Unconditional commit (Accepted Risk, no action).** The gate caused observed silent loss (browsers, banking apps, Termux-like terminals bouncing the input view mid-take); misdirection needs a narrow app-switch-in-callback race and is visible/user-correctable. Wrong-but-visible beats silent loss. `dropReason()` stays diagnostics-only (reason codes, PII-clean).
- **F2 — Partials suppressed (intentional).** Premature partial inserts would be worse than no feedback; the fallback notice covers the feedback half. Script limitation documented.
- **F3 — Soft guard now, LRU later.** Per-load R3 gates singles but N pinned large models have no total bound; docs alone leave Med sum-OOM open. Shipped: pin-time `availMem` refuse + summary precedence + RAM expectations. Full LRU/total-cap changes eviction semantics under pressure — scheduled separately with device testing. Leak hypothesis retired (wrappers hold no native handle; discarding `fresh` is GC-only).
- **F5 — Zero-byte compat (no action).** Strict non-emptiness was a functional bug (Hindi 0-byte configs); bomb bounds never rested on it. Zeroed `final.mdl` fails deterministically at native load (fail-closed, redacted via F7).
- **F6 — Corrected list accepted.** My "generic six"/`.pt` keep was wrong; shipped the auditor's verbatim list (exact 3 names + 4 suffixes, `.pt` redacts). Accepted cost: `phones.txt`/`mfcc.conf` redact; counts + leaf-gated lines bound the loss.
- **F7 — Keep `msg=` conditionally.** Load-phase missing-vs-corrupt distinction needs the message; transcript content not expected there; leaf+kind sits alongside; 300-char take bounds misses. Shipped extends + KDoc limit; otherwise would fall back to leaf+kind only.
- **F8 — Chose unconditional gates (a).** `required_status_checks` gates merges/PRs, not direct pushes — my protection proposal was insufficient. Shipped option (a): `lint` + `assembleRelease` unconditional again.
- **F9 — Docs-only.** Fail-closed on broken model is the correct priority (never silently jump models); confinement + resurrection behavior documented at declaration.
- **F10 — Never-weaken + transparency.** Direction-aware refusal alone would hide posture preservation inside bare `ignored N`; shipped named refused keys in report + `AppLog`.
- **F11 — Intentional gap + self-heal.** Opt-in formatting gap commented at call site; stale error left to self-heal (eager overwrite would fight the state machine).
- **F2-notice fix (was FAIL, now applied).** My "message only" caution was misplaced — error state is transient (next `onStateChanged` overwrites both, same as mic-permission/recognizer-error/password paths). `showEditorNotice()` now posts message + `STATE_ERROR`, mirroring `refuseVoiceInPassword()`.
- **Factual corrections recorded:** B7 disputed; counts/details corrected in B14, D2, D9, D14, I-17, I-19, R8, R11, R15, S6, S8, S11, S12, S14, S31 (all applied).

---

## Yet to fix — scheduled follow-ups (only open items)

- **F3-full LRU/total-cap** — needs eviction design + device testing.
- **B10 targetSdk, B21 flag flips** — need device testing; risky for JNA/Vosk.
- **B14 StateFlow, B15 DataStore** — refactors sequenced after P1.
- **SLSA attestation** — release/lint/PR smoke ships; attestation open.
- **S20 Weblate, S22 RTL QA** — external systems / device QA.
- **B18/B31 release-license proof** — needs signed release verification.

---

## Closure

All §§14.5/14.7 authorized items closed (F2-notice verified); F1/F5 remain closed Accepted Risk/compat. Remaining work is the list above only. Audit complete.
