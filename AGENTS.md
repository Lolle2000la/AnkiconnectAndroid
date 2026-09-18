# AGENTS.md

Personal vendor fork of [KamWithK/AnkiconnectAndroid](https://github.com/KamWithK/AnkiconnectAndroid)
(upstream is effectively unmaintained). Remote: `Lolle2000la/AnkiconnectAndroid`.

## Identity (easy to get wrong)
- **`applicationId` is `com.lolle2000la.ankiconnectandroid`, but the Java package/namespace is
  still `com.kamwithk.ankiconnectandroid`.** Intentional; don't "fix" it. Components (adb, the
  FileProvider authority, the local-audio dir) use the applicationId; classes/imports use
  `com.kamwithk...`.
- `${applicationId}` is the FileProvider authority and `BuildConfig.APPLICATION_ID` is the
  local-audio path; both update automatically. Changing the id again breaks update continuity.

## Toolchain
- AGP 9.4.0, Gradle 9.7.1, **JDK 21 required** (Robolectric API 36+ fails on JDK 17),
  compileSdk/targetSdk 37, Java 17 bytecode, minSdk 26. SDK path comes from gitignored
  `local.properties`. `com.github.ankidroid:Anki-Android` comes from JitPack.

## Commands (order matters)
- Format before committing: `./gradlew spotlessApply` (check: `./gradlew spotlessCheck`).
- Unit tests: `./gradlew testDebugUnitTest`; one class:
  `./gradlew testDebugUnitTest --tests 'com.kamwithk.ankiconnectandroid.request_parsers.ParserTest'`.
- Build: `./gradlew assembleDebug` / `assembleRelease` (release is unsigned without
  `keystore.properties`; still runs `lintVital`).
- CI runs `spotlessCheck` → `testDebugUnitTest` → `assembleDebug` on JDK 21.

## Where things live
All Java is in `app/src/main/java/com/kamwithk/ankiconnectandroid/`.
- `MainActivity` — launcher; notification channel/permission; Start/Stop from `ServiceState`.
- `Service` — the NanoHTTPD server as a `specialUse` foreground service on **port 8765**;
  `START_STICKY`; lifecycle drives `ServiceState`; retries the port bind on start. It posts an
  ongoing notification with `setOnlyAlertOnce` and a `Stop Service` action (`ACTION_STOP`, handled
  in `onStartCommand`), and — when the `pause_server_when_screen_off` pref is on — closes/reopens
  the listening socket on `SCREEN_OFF`/`SCREEN_ON` (the FGS itself stays up).
- `BootReceiver` — optional autostart on `BOOT_COMPLETED`/quickboot (`start_on_boot` pref).
- `LocalAudioImportService` — `dataSync` FGS importing the DB; writes
  `PREF_LAST_RESULT = "local_audio_last_import_result"`; implements `Service.onTimeout`.
- `ServiceState` — process-global STOPPED/STARTING/RUNNING/STOPPING + listeners.
- `SettingsActivity` — preferences, SAF import, battery/CORS/overlay.
- `routing/` — `Router` (binds **loopback only** at `127.0.0.1`; route table: `/` → `RouteHandler`,
  `/localaudio/(.)+` → `LocalAudioRouteHandler`; the commented `:source` route syntax does **not**
  work), `RouteHandler` (API body parse/CORS + API-key check), `APIHandler` (dispatch),
  `AnkiAPIRouting` (action switch + envelopes), `ApiKey` (generated key + per-request auth),
  `ForvoAPIRouting` (scraped Forvo), `LocalAudioAPIRouting`,
  `database/` (Room + singleton + importer), `localaudiosource/` (per-source name/URL).
- `request_parsers/` — `Parser` (JSON extractors + `gson`/`gsonNoSerialize`), `NoteRequest`,
  `MediaRequest`.
- `ankidroid_api/` — `IntegratedAPI` facade + `NoteAPI`/`CardAPI`/`DeckAPI`/`ModelAPI`/`MediaAPI`,
  `BinaryFile`, `Utility`; AnkiDroid access via `AddContentApi`/`FlashCardsContract`.

## Request flow & adding an action
`Router` → `RouteHandler` → `APIHandler` → (`AnkiAPIRouting` | `ForvoAPIRouting`) →
`IntegratedAPI` → `*API` → AnkiDroid. `APIHandler` diverts form requests that have
`term`/`expression` **and** `reading` to `ForvoAPIRouting`; everything else is JSON.

`RouteHandler` and `LocalAudioRouteHandler` call `ApiKey.verify` before dispatching. It returns
`{"result":null,"error":"valid api key must be provided"}` unless the caller is loopback (exempt by
default) or presents the key as a top-level JSON `key`, a `key` parameter, or an `X-Api-Key` header;
`requestPermission` is exempt, like desktop AnkiConnect.

To add an AnkiConnect action:
1. `request_parsers/Parser` — add a `getX(...)` extractor for any new `params` fields.
2. `routing/AnkiAPIRouting.findRoute` — add `case "x":` plus a private method returning a JSON
   string; `throw` on unsupported input so the error envelope is built.
3. `ankidroid_api/IntegratedAPI` or the matching `*API` class — implement the AnkiDroid call.
4. `docs/api.md` — document it under the right section (required).

Non-obvious wiring:
- nanohttpd-nanolets creates a **new handler instance per request**, so the `apiHandler`/`routing`
  null-check "caches" are dead; an `IntegratedAPI`/`AddContentApi` is built per request.
- `version` defaults to 4 (`Parser.get_version`); `formatSuccessReply` wraps only for `version > 4`;
  `findRouteHandleError` applies the envelope once, so action methods return **unwrapped** JSON.
  `multi` recursively re-enters `findRoute` and wraps each element.
- `Parser.gson` uses `serializeNulls()` (so `error:null` and null fields are emitted);
  `gsonNoSerialize` is used only by `canAddNotesWithErrorDetail`.

## Local audio
- Same server/port as the API: `GET /localaudio/get/` (source list) and
  `GET /localaudio/<source>/<file>` (bytes). **5050 is the desktop Python server only**; never
  add a second port (`LocalAudioSource.NETLOC = localhost:Service.PORT`).
- Sources, order fixed by the `LinkedHashMap` in `LocalAudioAPIRouting`: `nhk16`, `shinmeikai8`,
  `forvo`, `jpod`, `jpod_alternate`. `mediaDir`/`getMediaDir()` is dead config.
- DB: `entries` = text/metadata, `android` = the **BLOB** audio store (names are
  counter-intuitive). One shared Room instance (`LocalAudioDatabase`, journal `TRUNCATE`);
  **never open a DB per request** (that leaked connections → #69).
- Import: SAF → `LocalAudioImporter` (stream to `android.db.part`, validate SQLite magic + both
  tables, `ATOMIC_MOVE` swap); the old DB keeps serving until the swap succeeds. Path is fixed to
  `getExternalFilesDir(null)/android.db`.
- The source filter only applies when `sources.size() != knownSourceCount`, and Room validates the
  schema on first open — a DB whose column types/nullability don't match the entities can fail at
  query time even though the importer's table check passed.

## Settings / resources
- Pref key → reader: `cors_host` → `RouteHandler` (CORS headers); `start_on_boot` → `BootReceiver`;
  `forvo_language` → `Scraper`; `import_local_audio_db`/`local_audio_db_status`/
  `delete_local_audio_db` → `SettingsActivity`; `access_overlay_perms`; `disable_battery_optimization`
  (opens the system dialog, nothing stored); `pause_server_when_screen_off` → `Service` (closes the
  listening socket while the screen is off); `allow_lan_access` → `Router.resolveBindHost` (wildcard
  vs `127.0.0.1`) and `Service` (reopens the socket when it changes); `api_key`/`require_api_key`/
  `require_api_key_from_loopback` → `ApiKey` (auth policy) and `AnkiAPIRouting.requestPermission`
  (`requireApikey`). Storage-location prefs were removed.
- **Trap:** `SettingsActivity` looks up `cors_hostname`, but the key is `cors_host` (dead handler).
- Manifest components: `Service` (exported, `specialUse`), `LocalAudioImportService` (`dataSync`),
  `BootReceiver`, `FileProvider` authority `${applicationId}`.

## Gotchas (hard-earned)
- **AnkiDroid exposes no real card IDs.** `findCards` synthesises `(noteId << 10) | ord` (`CardAPI`);
  `cardsInfo` returns `[]`; `suspend` resolves the synthesised IDs via the `ReviewInfo` schedule URI.
  Keep encode/decode and `suspend` in sync.
- **`guiBrowse` must search all decks.** AnkiDroid's `anki://x-callback-url/browser` deep link
  hardcodes `allDecks=false` (scopes to the last selected deck), so notes in other decks look
  missing. We launch `com.ichi2.anki.CardBrowser` directly with extras `search_query` +
  `all_decks=true`, falling back to the deep link (newer AnkiDroid un-exports CardBrowser →
  catch `SecurityException`).
- **FGS types matter:** the server is `specialUse` so it can start from `BOOT_COMPLETED` on
  Android 15+; the importer is `dataSync` (6h/24h cap + `onTimeout`). Don't switch the server.
- **Outbound calls must keep timeouts.** `MediaAPI.downloadMediaFile` (`HttpURLConnection`) and
  `Scraper` (`Jsoup`) set explicit connect/read timeouts; without them a stalled remote host pins a
  request thread and keeps the radio awake (`URLConnection` defaults to no timeout at all).
- **The server binds loopback by default.** `Router.resolveBindHost` returns `127.0.0.1` unless the
  `allow_lan_access` pref is set, in which case it passes a null host to NanoHTTPD (wildcard). Keep
  the default: a listening socket on the Wi-Fi interface may keep the Wi-Fi/BT combo chip out of its
  low-power state even though `batterystats` attributes no CPU/radio to the app. `adb forward`
  still works (it targets device loopback).
- **The API key mirrors desktop AnkiConnect.** Top-level JSON `key` (also accepted as a `key`
  parameter or `X-Api-Key`); failure is `{"result":null,"error":"valid api key must be provided"}`;
  `requestPermission` is exempt. The key value is per-install — desktop's `apiKey` is a separate
  setting, and the key field here is editable if they need to match.
- **No broad storage access:** DB path is fixed; `MANAGE_EXTERNAL_STORAGE` was removed. Don't
  reintroduce configurable paths/permissions.
- **Spotless is ratcheted** (`ratchetFrom 'origin/master'`): only files changed since
  `origin/master` are checked, and `origin/master` must exist (CI checks out full history).
- **Robolectric** needs the JDK `--add-opens`/`--add-exports` flags in `app/build.gradle` and
  `sdk=36` in `app/src/test/resources/robolectric.properties`. Don't remove either.
- `ServiceState` is process-global; tests must reset it (see `ServiceStateTest`).
- Global git config has `tag.gpgSign=true` (SSH signing): `git tag vX` opens nano. Always
  `git tag -s vX -m "..."`.

## Tests
- `app/src/test/**` (Robolectric + plain JUnit) is the real suite; `app/src/androidTest/**` is only
  the template `ExampleInstrumentedTest`, so skipping `connectedAndroidTest` is expected.
- Robolectric coverage: `LocalAudioDatabaseTest` (singleton), `LocalAudioImporterTest`
  (validate/swap/cancel/delete), `UtilityTest`, `CardIdEncodingTest`, `ParserTest`.
  Plain JUnit: `ServiceStateTest`, `ExampleUnitTest`.

## Release
- `git tag -s vX.Y.Z -m "..." && git push origin vX.Y.Z` → `.github/workflows/release.yml` builds a
  signed APK and publishes a GitHub Release (Obtainium). `versionName` from the tag;
  `versionCode = 100000 + run_number` (local default is 15).
- Signing: `keystore.properties` (gitignored) locally or repo secrets in CI — see
  `docs/self-hosting.md`.
- Assets upload sequentially via `gh release upload --clobber` with retries; don't switch back to
  parallel-uploading actions (they left incomplete drafts).
- Actions run the workflow **from the tagged commit**, so workflow fixes only apply to later tags.

## Docs to keep in sync
- `docs/api.md` — supported AnkiConnect actions (update when adding/changing one).
- `docs/self-hosting.md` — signing, releases, ADB start, battery/Play Protect.
- `docs/upstream-issue-triage.md` — upstream issue/PR analysis; read before "fixing" upstream things.
- `fastlane/metadata/android/` — store listing metadata.
