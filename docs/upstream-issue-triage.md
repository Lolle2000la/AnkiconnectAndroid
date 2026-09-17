# Upstream issue triage & improvement plan

Analysis of all **8 open issues** in [KamWithK/AnkiconnectAndroid](https://github.com/KamWithK/AnkiconnectAndroid),
plus a design for supplying the local-audio database entirely inside the app.

Each issue was analysed against the **5 open upstream PRs**: #105, #101, #95, #90, #84.

> Analysis-only. Nothing here has been implemented. Confidence levels are as reported by the
> individual analyses; treat `medium` items as needing verification before acting.

## Summary

| Issue | Title | Open PR fixes it? | Recommended action | Effort |
|---|---|---|---|---|
| [#107](#issue-107) | Support starting the service via ADB？ | No | Add `android:exported="true"` to `.Service` | S |
| [#103](#issue-103) | Yomitan missing Anki buttons for existing notes | No (PR #101 is a partial prerequisite) | Return `cards: []` + implement `cardsInfo` | S |
| [#102](#issue-102) | Blocked by Play Protect | No | Not code-fixable; minimise permissions + docs; fork id/signing already helps | S–M |
| [#86](#issue-86) | Copying audio db on Android 11+ | No (already fixed by merged #78) | Harden `getDB()` / read-only fallback | M |
| [#75](#issue-75) | Error adding card with "suspend new cards" on | No (PR #101 only does `findCards`) | Add `findCards` + `suspend` + proper error JSON | M |
| [#74](#issue-74) | Autostart | No | `BOOT_COMPLETED` receiver (+ optional setting) | S–M |
| [#69](#issue-69) | Local audio starts refusing requests | No | Singleton `EntriesDatabase` + null guards | S–M |
| [#33](#issue-33) | Add source code formatting? | No | `.editorconfig` + Spotless + CI check | M |
| — | Local audio in-app data supply | — | SAF import + atomic swap (see [design](#local-audio-in-app-data-supply)) | M–L |

### Open PR assessment at a glance

| PR | Title | Verdict |
|---|---|---|
| #105 | Fix MalformedJsonException crash on requestPermission action | Genuinely useful, but fixes a **different** issue (KOReader `requestPermission`); no tracked issue in this list. Good small fix to cherry-pick. |
| #101 | Find cards implementation with ankiconnect | **Draft, insufficient.** Adds `findCards`/`cardsInfo` but not `suspend`; depends on an AnkiDroid `/cards` endpoint (≥ 2.24.0); replaces batched `notesInfo` with N+1 queries; uses stale scheduling column names. Treat as reference, do not merge as-is. |
| #95 | Allow multiple trusted CORS hosts instead of only one | Unrelated to any issue; plausibly useful. |
| #90 | Fix: Accept numeric version field in AnkiConnect API requests | Unrelated to any issue (KOReader parser). |
| #84 | fix forground service issue for Android 15 | Unrelated to the tracked issues; partially relevant to #74 (adds `foregroundServiceType`, auto-start on `onStart`, `onDestroy` null-guard) but does **not** add a boot receiver and makes the Stop button ineffective. Cherry-pick only the safe bits. |

**Key finding:** no open PR resolves any of the 8 open issues. Two of them (#75, #103) overlap with PR #101, but #101 is incomplete and would still leave the Yomitan flows broken.

---

## Issue #107: Support starting the service via ADB？

**Verdict:** NO-OPEN-PR · **Effort:** S · **Confidence:** high (manifest gap), medium (exact `am` subcommand)

The `.Service` component is not exported (`app/src/main/AndroidManifest.xml:58`), so `adb shell am startservice` fails. Feature request by the reporter, no maintainer response, no prior attempt.

**Plan**
1. `AndroidManifest.xml`: `<service android:name=".Service" android:exported="true" />`.
2. Document: `adb shell am start-foreground-service com.lolle2000la.ankiconnectandroid/com.kamwithk.ankiconnectandroid.Service` (applicationId vs Java package differ in this fork). `am startservice` may be blocked by Android 12+ background-FGS rules — prefer `start-foreground-service`.
3. Risk: exported + no permission lets any app start/stop the service (it only runs a localhost HTTP server). Acceptable for a sideloaded personal fork; a signature permission would defeat the ADB use case.
4. Test: start/stop via adb, `adb forward tcp:8765` + curl, reboot regression.

---

## Issue #103: Yomitan missing Anki buttons for existing notes

**Verdict:** NO-OPEN-PR · **Effort:** S (~40–60 LOC) · **Confidence:** high

Yomitan calls `findNotes` → `notesInfo` and validates every note with a schema that requires `cards: number[]`. The app's `NoteInfo` (`ankidroid_api/NoteAPI.java:141-170`) omits `cards`, so Yomitan throws and hides all Anki buttons. The reporter's suggested fix (read card IDs) is **not viable on stock AnkiDroid**: the pinned contract (`2.17alpha14`) and current stable do not expose a `/cards` route or `Card._ID` (only AnkiDroid `main`/≥ 2.24.0 does). PR #101 does not fix this (its `notesInfo` still omits `cards`), though its `cardsInfo` is a follow-on prerequisite.

**Plan**
1. `NoteAPI.java`: add a non-null `List<Long> cards` to `NoteInfo`; emit `Collections.emptyList()` from `notesInfo()`. (Must not be `null`: Gson has `serializeNulls`, so `null` re-emits `cards: null` and still fails.) Best-effort: try `/notes/<id>/cards` and fall back to empty.
2. `AnkiAPIRouting.java`: add `case "cardsInfo"` returning a JSON array (empty is fine). Yomitan calls `cardsInfo` unconditionally after `notesInfo`, and the current default route returns the string `"AnkiConnect v.6"`, which also fails.
3. Do **not** add `findCards` here (out of scope).
4. Test: unit-test Gson output includes `cards`; manual repro with the issue's example words; confirm no `expected number[], received undefined` / `cardsInfo` error.

---

## Issue #102: Blocked by Play Protect

**Verdict:** NO-OPEN-PR (not code-fixable) · **Effort:** S (manifest/docs), M (full SAF rework) · **Confidence:** high that no PR fixes it; medium on the exact Play Protect trigger

Play Protect blocks/uninstalls the APK on a Pixel 8 / Android 16; 11 comments, reported across regions. The maintainer filed an appeal with no response. Root cause is **distribution/trust**, not a code bug: v1.13 and v1.15 have identical permissions and `targetSdk`, yet only v1.15 is blocked. `MANAGE_EXTERNAL_STORAGE` was added *after* v1.15 (#78) and is not the cause.

**Mitigations (no guaranteed outcome)**
1. This fork already applies the strongest mitigation (distinct applicationId + own signing key) — decouples from upstream's blocked package/signature. Do **not** change the id again; it breaks update continuity.
2. Minimise permissions: `SYSTEM_ALERT_WINDOW` and `READ_EXTERNAL_STORAGE` are declared but unreferenced in code. `MANAGE_EXTERNAL_STORAGE` is only needed for arbitrary user-chosen local-audio folders — the SAF import design below removes that need entirely.
3. Document install troubleshooting (allow install anyway, Obtainium/ADB).

---

## Issue #86: Copying audio db on Android 11+

**Verdict:** PARTIAL — the substantive fix already landed as **merged PR #78** and is in this fork · **Effort:** M · **Confidence:** high (analysis), medium (Room failure detail without a device)

Android 11+ hides `Android/data/<pkg>/files` from file managers, so the README's adb-based copy is the only route. PR #78 added `MANAGE_EXTERNAL_STORAGE` plus configurable storage location/path, and this fork already carries it (plus our fix deriving the default path from `BuildConfig.APPLICATION_ID`). Upstream #86 was never auto-closed. Remaining hardening for the related #91 `SQLiteException: Could not open ... read/write`:

**Plan**
1. `LocalAudioAPIRouting.getDB()` (`:82-104`): it only checks `Files.isReadable` and otherwise lets Room open read-write, silently creating an empty DB. Check existence **and** parent-dir writability; fall back to `getExternalFilesDir(null)` with a clear error, or open genuinely read-only via `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` / copy-on-first-use.
2. `SettingsActivity.java` storage prefs (`:109-129`): verify default persistence; guard `Paths.get(...)` against null/blank values (the XML default was removed in this fork — the runtime setter may not persist; medium confidence).
3. Sidecar handling (`-shm`/`-wa`) when swapping DBs.
4. Docs: elevate the adb fallback and folder-choice guidance.

> This overlaps strongly with the local-audio redesign; the singleton/reopen work from #69 is a prerequisite.

---

## Issue #75: Error adding a card with "suspend new cards" on

**Verdict:** PARTIAL — PR #101 does only half · **Effort:** M · **Confidence:** high (diagnosis), medium (fork-specific provider behaviour)

With "Suspend new cards" enabled, Yomitan calls `addNote` → `findCards({query:"nid:<id>"})` → `suspend({cards:[...]})`. Neither action exists in `AnkiAPIRouting.findRoute()` (`:44-89`), so the `default:` branch returns the non-JSON string `"AnkiConnect v.6"` (`:138-140`), and re-parsing yields `MalformedJsonException` (the `.` in `v.6`). The note is added but never suspended.

PR #101 adds `findCards` but **not** `suspend`, and its card querying uses stale/incorrect scheduling column names (`ease_factor`/`reviews` vs `sm2_factor`/`reps`). No open PR implements `suspend`.

**Plan**
1. `AnkiAPIRouting.java`: add `case "findCards"` → JSON long[]; `case "suspend"` → JSON `true`; make `default:` return a proper AnkiConnect error object (`{"result":null,"error":"unsupported action: X"}`) so unknown actions never produce `MalformedJsonException`.
2. `Parser.java`: add `getCardQuery` (reuse `getNoteQuery`) and `getSuspendCardIds`.
3. New `CardAPI` (or extend `NoteAPI`): `findCards(query)` via `content://…/cards` (AnkiDroid ≥ 2.24.0) with a fallback for older versions; `suspendCards(ids)` via `content://…/schedule` `ReviewInfo` (`NOTE_ID` + `CARD_ORD` + `SUSPEND=1`), which **is** available in the pinned contract.
4. `IntegratedAPI.java`: add the `cardAPI` field.
5. Edge cases: multi-card note types, already-suspended, `multi` action, provider authority (release vs debug), older AnkiDroid graceful error.
6. Test: unit-test parsers; manual with AnkiDroid ≥ 2.24 + Yomitan, reversed/multi-card notes, regression of addNote/findNotes/notesInfo.

> Requires AnkiDroid ≥ 2.24.0 for real card IDs; the fallback path mitigates older installs.

---

## Issue #74: Autostart

**Verdict:** NO-OPEN-PR · **Effort:** S (core) / M (with setting) · **Confidence:** high

Users must tap "Start Service" after every reboot. Not implemented; maintainer said he'd review a PR but none exists. No open PR adds a boot receiver (PR #84 adds FGS type + auto-start only when the app is opened, and breaks the Stop button).

**Plan**
1. `AndroidManifest.xml`: add `RECEIVE_BOOT_COMPLETED`; add an exported=false receiver for `BOOT_COMPLETED` (+ vendor `QUICKBOOT_POWERON`). Do **not** handle `LOCKED_BOOT_COMPLETED` (Room + prefs need unlock).
2. New `BootReceiver.java` → `ContextCompat.startForegroundService(ctx, new Intent(ctx, Service.class))`.
3. `Service.java`: null-guard `server.stop()` in `onDestroy` (from #84).
4. Optional "Start on boot" preference (default on).
5. Constraints: force-stopped apps get no boot event; OEM autostart managers may still block; if `targetSdk` rises to 35, `dataSync` cannot start from boot (use `specialUse`/WorkManager).

---

## Issue #69: Local audio starts refusing requests

**Verdict:** NO-OPEN-PR · **Effort:** S–M · **Confidence:** medium on root cause, high that the leak is real

After many rapid, interrupted audio lookups, `/localaudio/get/` intermittently fails while the source-list endpoint keeps working. No logs/stack traces were provided; the report was never re-confirmed. Root-cause hypothesis: `LocalAudioAPIRouting.getDB()` (`:82-100`) builds a **new Room database per request and never closes it**, leaking native SQLite connections/file descriptors; unreachable instances are only reclaimed on GC (matching the user's "recovers if you wait"). NanoHTTPD's unbounded thread-per-connection runner is a plausible secondary contributor. No fork has fixed this.

**Plan**
1. Singleton DB holder (`EntriesDatabase.getInstance(context)`, double-checked locking, `JournalMode.TRUNCATE`); reopen only when the configured path changes; use read/write locks for clean swaps. Close DBs after use in the source/audio handlers.
2. Fix handler reuse: NanoHTTPD instantiates route resources per request, so the existing handler caches are dead — hold them at service/application scope.
3. Null-guard `getData` in `getAudioHandleError` (`:243-273`).
4. Diagnostics to confirm: monitor FD/thread count under load; temporary instance counter in `getDB()`.
5. Test: >200 sequential requests with FD-count monitoring, then the interrupted-autoplay sequence.

> This is the same singleton refactor that is Phase A of the local-audio design below.

---

## Issue #33: Add source code formatting?

**Verdict:** NO-OPEN-PR · **Effort:** M · **Confidence:** high

Proposal to adopt a formatter + CI enforcement. Maintainer was supportive but said CI "likely would not be worth it right now" because there was no CI at that time. No PR ever opened. The codebase is pervasively snake_case and has no style config.

**Plan**
1. Commit `.editorconfig` (4-space, LF, ~120 col) so Android Studio/IntelliJ picks it up without tracking `.idea/`.
2. Spotless with `palantirJavaFormat()` (4-space/120-col google-java-format fork) to avoid a 2-space reformat of the whole codebase. Use `ratchetFrom 'origin/master'` to migrate incrementally.
3. Add a CI workflow running `./gradlew spotlessCheck` (plus `assembleDebug`), compatible with our existing `.github/workflows/release.yml`.
4. Keep snake_case→camelCase as a separate, later refactor.
5. Risks: one-time diff churn; Palantir/GJF JDK-17 module handling is managed by Spotless.

---

## Local audio in-app data supply

Goal: let the user supply `android.db` entirely inside the app — no adb, no file-manager copy.

### How it works today

- `routing/LocalAudioAPIRouting.getDB()` (`:82-100`) computes `Paths.get(storage_location, storage_dir_path, "android.db")` from prefs, falls back to `getExternalFilesDir(null)/android.db`, and builds a Room DB **per request, without closing it**.
- The audio is embedded as BLOBs in the `android(id, file, source, data)` table inside `android.db`; `entries(...)` holds text entries. `user_files/` is desktop-only and **not** needed on device — only `android.db` (plus Room sidecars) matters.
- Manifest requests `READ_EXTERNAL_STORAGE` and `MANAGE_EXTERNAL_STORAGE`.

### Pain points

- Android 11+ blocks file managers from `Android/data/<pkg>/files`; SAF is the only permission-free read path.
- Multi-GB DB ⇒ streaming copy with progress, 2× free space, no full-file buffering.
- Room opens the DB read-write and creates `-wal`/`-shm`; copying in place is corruption-prone, hence the README's "close the app, delete sidecars" dance.
- Per-request Room instances (see #69) and silent empty-DB creation (#71) make failures look like "no audio".

### Options considered

1. **SAF `ACTION_OPEN_DOCUMENT` → stream-copy into app storage + atomic swap (recommended).** No permissions; works from Downloads/SD/cloud; costs 2× space and copy time.
2. SAF folder/zip picker — modest extra convenience, little size benefit (audio already compressed).
3. Download from a user URL to `.part` then swap — good additive option.
4. Read picked DB in place read-only — **rejected**: Room needs write access/WAL, cloud FDs are unreliable.
5. Keep adb/manual copy — does not solve the ask.

### Recommended design

Import the user's clean `android.db` via SAF into the app-specific dir, streamed to `android.db.part`, fsync + validate (SQLite magic, `PRAGMA quick_check`, required tables/row counts), then atomically rename over `android.db` while a **singleton** DB is closed/reopened. Drop broad storage permissions for the default flow.

### Implementation phases

- **Phase A — DB singleton & robustness (prerequisite for everything, also fixes #69).** New `routing/database/LocalAudioDatabase.java` holding the `EntriesDatabase` + a `ReentrantReadWriteLock`; `JournalMode.TRUNCATE`; replace per-request builders; close in handlers; surface "no database installed" as a clear HTTP error.
- **Phase B — Import UX.** New "Local Audio Database" preference category (`import`, `status`, `delete`); `registerForActivityResult(OpenDocument)` with `takePersistableUriPermission`; new `localaudio/LocalAudioDbImporter.java` (free-space check via `StatFs`, buffered streaming copy, progress, cancel, validate, sidecar-safe atomic swap); new foreground import service reusing the persistent notification with progress + cancel; manifest adds `foregroundServiceType="dataSync"` and removes `READ_EXTERNAL_STORAGE`/`MANAGE_EXTERNAL_STORAGE`.
- **Phase C — Add-ons.** Folder/zip import and/or URL download to `.part`.
- **Phase D — Docs/tests.** Make SAF the primary documented path.

### Testing

Unit: path resolution, free-space math, validation (good/truncated/non-SQLite/wrong schema), `.part` cleanup, swap invariants.
Instrumented: API 30/33/35; pick from Downloads/SD/cloud; 5 GB import; low-space; interruption leaves old DB intact; concurrent requests during swap; auto-register an existing adb-placed DB; no-DB error; memory profiling.

### Risks & open questions

- Atomic rename across adopted/SD/cloud destinations may need a delete+rename fallback (brief unavailable window).
- Confirm Room can open without write access for a read-only legacy path; if not, copy-on-first-use.
- Whether to support WAL-mode source DBs (checkpoint first?).
- How long to keep the legacy `MANAGE_EXTERNAL_STORAGE` path.
- FGS type / WorkManager for `targetSdk ≥ 34`.

**Effort:** Phase A **M**, Phase B **M–L**, Phase C **M**, Phase D **S**.

---

## Cross-cutting observations & suggested sequencing

1. **Shared root cause.** Issues #69, #86, #91 all stem from how the local-audio DB is opened (per-request, read-write, silent empty creation). A single singleton/atomic-swap refactor (Phase A above) addresses all of them and is a prerequisite for the in-app import.
2. **PR #101 should not be merged**, but its `findCards`/`cardsInfo` work is useful input for #75/#103; those fixes need `suspend`, `cards: []`, `cardsInfo`, and proper error JSON.
3. **AnkiDroid version dependency.** Real card IDs require AnkiDroid ≥ 2.24.0; implement graceful fallbacks.
4. **Permission minimisation (#102) and the SAF import reinforce each other**: once local audio is imported via SAF, `MANAGE_EXTERNAL_STORAGE` can likely be dropped, reducing the app's footprint.
5. **No open PR is a ready-made fix for any tracked issue.** The fork should implement the small fixes itself; cherry-pick only the safe, relevant parts of #84/#105.

### Possible order of work (for discussion)

1. Quick, low-risk wins: **#107** (exported), **#33** (formatting + CI), **#74** (boot receiver).
2. Reliability prerequisite: **#69** (DB singleton) — also unblocks local audio.
3. Yomitan correctness: **#103** (cards/`cardsInfo`) then **#75** (`findCards`/`suspend`/error JSON).
4. Local audio in-app import (Phases A–D), which resolves **#86** and the original adb pain point.
5. **#102** permission minimisation + install docs (after SAF import lands).
