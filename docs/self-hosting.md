# Self-hosting this fork

This repository is a personal vendor fork of
[KamWithK/AnkiconnectAndroid](https://github.com/KamWithK/AnkiconnectAndroid).
It uses a distinct `applicationId` (`com.lolle2000la.ankiconnectandroid`) so it
can be installed and updated independently of the upstream app.

Updates are delivered through **GitHub Releases**, consumed by
[Obtainium](https://github.com/ImranR98/Obtainium). A signed, self-hosted
[F-Droid repository](#optional-self-hosted-f-droid-repository) can be layered on
later if desired.

## 1. Create a release signing key (once)

A single, stable key must sign every release, otherwise Android refuses to
install an update over the previous version.

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias release \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

- Keep this file and its passwords safe and backed up. There is no recovery:
  losing it means users must uninstall and reinstall.
- `release.jks` and `keystore.properties` are already gitignored. Never commit them.

Encode the keystore for GitHub Actions:

```bash
base64 -w0 release.jks > release.jks.b64
```

## 2. Configure GitHub Actions secrets

In **Settings → Secrets and variables → Actions**, add:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | contents of `release.jks.b64` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `release` (or your alias) |
| `SIGNING_KEY_PASSWORD` | key password (same as store password for PKCS12) |

## 3. Cut a release

Update `versionCode`/`versionName` defaults in `app/build.gradle` if you like,
then tag and push:

```bash
git tag v1.16.0
git push origin v1.16.0
```

The [`release` workflow](../.github/workflows/release.yml) then:

1. builds a signed release APK,
2. derives `versionName` from the tag and a monotonically increasing
   `versionCode` from the Actions run number (offset by 100000 so it always
   outranks local default builds),
3. attaches `AnkiconnectAndroid-<version>.apk` and a `.sha256` file to a new
   GitHub Release.

You can also run the workflow manually (**Actions → Release → Run workflow**) to
produce an APK build artifact without publishing a release.

## 4. Set up automatic updates with Obtainium

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. **Add App** and enter the repository URL:
   `https://github.com/Lolle2000la/AnkiconnectAndroid`
3. Obtainium detects GitHub Releases automatically. If it prompts for an APK
   filter, use `AnkiconnectAndroid-.*\.apk`.
4. Enable background update checks. Obtainium compares the APK `versionCode`
   against the installed one and installs new releases for you.

## Building and signing locally

```bash
cp keystore.properties.example keystore.properties
# edit keystore.properties: storeFile=release.jks, plus passwords/alias
./gradlew assembleRelease
```

The signed APK is written to
`app/build/outputs/apk/release/app-release.apk`. Verify the certificate with:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Installation and Play Protect

These APKs are **not** distributed through Google Play, so Play Protect may warn
about them or offer to remove them. This is a distribution/trust signal, not a
sign of a problem with the build. If Play Protect blocks installation:

- On the warning dialog, choose **Install anyway** / open **More details** and
  continue.
- Or temporarily disable Play Protect scanning while installing, then re-enable it.
- Obtainium and `adb install` usually bypass the interactive warning.

The app is signed with your own release key and has its own package name
(`com.lolle2000la.ankiconnectandroid`), independent of upstream.

## Starting the service from ADB (or another app)

The service is exported, so it can be started without opening the app. Note that the
component is the application ID plus the Java package of the class:

```bash
adb shell am start-foreground-service \
  com.lolle2000la.ankiconnectandroid/com.kamwithk.ankiconnectandroid.Service
adb forward tcp:8765 tcp:8765
curl 'http://localhost:8765/?action=version'
```

Stopping it:

```bash
adb shell am stop-service \
  com.lolle2000la.ankiconnectandroid/com.kamwithk.ankiconnectandroid.Service
```

Because the foreground service is also started from `BOOT_COMPLETED`, the same
component path applies to automation apps such as Tasker.

> The server binds to `127.0.0.1` (loopback) only. `adb forward tcp:8765 tcp:8765` still works
> because it connects to the device's loopback, but other devices on the network cannot reach it.

## Optional: self-hosted F-Droid repository

The same signed APKs can feed a self-hosted F-Droid repository served from
GitHub Pages. This gives you the standard F-Droid client experience (repo URL +
fingerprint) and is a good option if you later want one update channel for
several self-built apps.

Requirements:

- a **repository signing key** (separate from the APK signing key),
- [`fdroidserver`](https://f-droid.org/docs/Installing_the_Server_and_Repo_Tools/)
  in CI, run with `fdroid update` only (no build server needed),
- a `gh-pages` branch (or `/docs` on the default branch) configured as the Pages
  source.

Sketch of a workflow to add as `.github/workflows/fdroid.yml`, reusing the
release APKs:

```yaml
name: F-Droid repo

on:
  release:
    types: [published]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  repo:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: '17' }
      - uses: android-actions/setup-android@v4
      - name: Install fdroidserver
        run: pipx install fdroidserver
      - name: Download latest APK
        run: |
          gh release download --repo "$GITHUB_REPOSITORY" --pattern '*.apk' --dir repo
        env: { GH_TOKEN: ${{ secrets.GITHUB_TOKEN }} }
      - name: Restore repo config + signing key
        env:
          REPO_KEYSTORE_BASE64: ${{ secrets.FDROID_KEYSTORE_BASE64 }}
          REPO_KEYSTORE_PASSWORD: ${{ secrets.FDROID_KEYSTORE_PASSWORD }}
        run: |
          echo "${REPO_KEYSTORE_BASE64}" | base64 -d > fdroid/keystore.jks
          # config.yml must set keystore: keystore.jks, repo_keystore_password, etc.
      - name: Update index
        working-directory: fdroid
        run: |
          fdroid update --create-metadata
      - name: Publish to gh-pages
        uses: peaceiris/actions-gh-pages@v4
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: fdroid/repo
          publish_branch: gh-pages
```

Then enable Pages for the `gh-pages` branch and add
`https://<user>.github.io/AnkiconnectAndroid/repo/` in the F-Droid client,
verifying its fingerprint. The signing-keystore and `config.yml` setup is a
one-time `fdroid init`; commit only `config.yml` (never the keystore) and store
the keystore in secrets.
