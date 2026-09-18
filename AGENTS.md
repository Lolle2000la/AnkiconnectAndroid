# AGENTS.md

Personal vendor fork of [KamWithK/AnkiconnectAndroid](https://github.com/KamWithK/AnkiconnectAndroid)
(upstream is effectively unmaintained). Remote: `Lolle2000la/AnkiconnectAndroid`.

## Identity (easy to get wrong)
- **`applicationId` is `com.lolle2000la.ankiconnectandroid`, but the Java package/namespace is
  still `com.kamwithk.ankiconnectandroid`.** This mismatch is intentional; don't "fix" it.
  Components (adb commands, FileProvider authority, local-audio dir) use the applicationId,
  classes/imports use `com.kamwithk...`.
- `${applicationId}` is the FileProvider authority and `BuildConfig.APPLICATION_ID` is the
  local-audio path; both update automatically. Changing the id again breaks update continuity.

## Toolchain
- AGP 9.4.0, Gradle 9.7.1, **JDK 21** (Robolectric API 36+ fails on JDK 17), compileSdk/targetSdk 37,
  Java 17 bytecode, minSdk 26. Android SDK path comes from the gitignored `local.properties`.
- `com.github.ankidroid:Anki-Android` comes from JitPack.

## Commands (order matters)
- Format before committing: `./gradlew spotlessApply` (check: `./gradlew spotlessCheck`).
- Unit tests: `./gradlew testDebugUnitTest`; one class:
  `./gradlew testDebugUnitTest --tests 'com.kamwithk.ankiconnectandroid.request_parsers.ParserTest'`.
- Build: `./gradlew assembleDebug` / `assembleRelease`.
- CI runs `spotlessCheck` → `testDebugUnitTest` → `assembleDebug` on JDK 21.

## Architecture
- One in-process NanoHTTPD server on **port 8765** (`Service`, a `specialUse` foreground service).
  It serves **both** the AnkiConnect API (`POST /`) **and** local audio
  (`GET /localaudio/get/`, `GET /localaudio/<source>/<file>`).
- Request flow: `Service` → `routing/Router` → `RouteHandler` (API) / `LocalAudioRouteHandler` (audio)
  → `ankidroid_api/*` (AnkiDroid via `AddContentApi`/`FlashCardsContract`).
- Local audio DB: one shared Room instance (`routing/database/LocalAudioDatabase`, journal
  `TRUNCATE`). Import via SAF (`LocalAudioImporter`: stream to `android.db.part` → validate →
  atomic swap) using `LocalAudioImportService`.
- UI state: `ServiceState` (STOPPED/STARTING/RUNNING/STOPPING + listeners) drives MainActivity's
  single Start/Stop control.

## Gotchas (hard-earned)
- **One port only.** 5050 is the *desktop Python* local-audio server; on Android everything is
  8765 (`LocalAudioSource.NETLOC = localhost:Service.PORT`). Never add a second audio port.
- **AnkiDroid exposes no real card IDs.** `findCards` synthesises IDs as `(noteId << 10) | ord`
  (`CardAPI`) and `cardsInfo` returns `[]`; `suspend` resolves them via the `ReviewInfo` schedule
  URI. Keep encode/decode and `suspend` in sync.
- **`guiBrowse` must search all decks.** AnkiDroid's `anki://x-callback-url/browser` deep link
  hardcodes `allDecks=false` (scopes to the last selected deck), so notes in other decks look
  missing. We launch `com.ichi2.anki.CardBrowser` directly with extras `search_query` +
  `all_decks=true`, falling back to the deep link (newer AnkiDroid un-exports CardBrowser →
  catches `SecurityException`).
- **Don't build a Room DB per request** — that leaked SQLite connections and caused local-audio
  failures (#69). Always go through `LocalAudioDatabase`.
- **FGS types:** server is `specialUse` so it may start from `BOOT_COMPLETED` on Android 15+;
  import is `dataSync` (6h/24h cap) and implements `Service.onTimeout`. Don't switch the server
  to `dataSync`.
- **No broad storage access:** the DB path is fixed to `getExternalFilesDir(null)/android.db`;
  `MANAGE_EXTERNAL_STORAGE` was removed. Don't reintroduce configurable paths/permissions.
- **Spotless is ratcheted** (`ratchetFrom 'origin/master'`): only files changed since
  `origin/master` are checked, and `origin/master` must exist (CI checks out full history).
- **Robolectric requirements:** JDK `--add-opens`/`--add-exports` in `app/build.gradle` and
  `sdk=36` in `app/src/test/resources/robolectric.properties`. Don't remove either.
- `ServiceState` is process-global; tests must reset it (see `ServiceStateTest`).
- Global git config has `tag.gpgSign=true` (SSH signing): `git tag vX` opens nano. Always use
  `git tag -s vX -m "..."`.

## Release
- `git tag -s vX.Y.Z -m "..." && git push origin vX.Y.Z` triggers
  `.github/workflows/release.yml`: signed APK + GitHub Release (consumed by Obtainium).
  `versionName` comes from the tag; `versionCode = 100000 + run_number` (local default is 15).
- Signing is `keystore.properties` (gitignored) locally or repo secrets in CI — see
  `docs/self-hosting.md`. Without it, `assembleRelease` is unsigned.
- Release assets upload sequentially via `gh release upload --clobber` with retries; do not switch
  back to parallel-uploading actions (they left incomplete drafts).
- Actions run the workflow **from the tagged commit**, so workflow fixes only apply to later tags.

## Docs to keep in sync
- `docs/api.md` — supported AnkiConnect actions; update when adding/changing an action.
- `docs/self-hosting.md` — signing, releases, ADB start, battery/Play Protect notes.
- `docs/upstream-issue-triage.md` — analysis of upstream issues/PRs (read before "fixing" things).
