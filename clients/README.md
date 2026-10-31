# Ratatoskr clients

Kotlin Multiplatform + Compose Multiplatform. `shared/` holds the API
client, models and every screen; `desktop/` is the desktop entry point and
packaging; `android/` is the Android app. Architecture and phase plan:
[`docs/development-plan.md`](../docs/development-plan.md).

**Status:** the Linux desktop app and the Android app are released
(GitHub Releases, tags `linux-v*` and `android-v*`). Windows and macOS come
from the same `desktop/` module and need only packaging and platform glue;
iOS is planned. What the apps can and can't do yet is summarised in the
[project README](../README.md#what-the-apps-dont-do-yet).

Needs JDK 21. On Fedora, packaging also needs `java-21-openjdk-jmods`
(jlink can't build the bundled runtime without it); Temurin JDKs include it.

## Three different things

| | What it is | How you get it |
|---|---|---|
| **Development build** | Runs straight from the source tree, using your JDK | `./gradlew :desktop:run` |
| **Packaged app** | AppImage / `.deb` / `.rpm` with its own bundled Java runtime | GitHub Releases, or build locally (below) |
| **Installed app** | The `.rpm`/`.deb` installed: in the app launcher, `ratatoskr` on `PATH` | `sudo dnf install ./ratatoskr-*.rpm` |

Users only ever deal with the last two.

## Tests

```bash
./gradlew :shared:jvmTest
```

`LiveServerTest` only runs when `RATATOSKR_TEST_SERVER` points at a real server.

### Trying changes against a throwaway server

Run a scratch server with its own data directory, from the virtualenv set
up in the server README's "Local development" section (lower Argon2 memory
makes unlocking quicker on a dev machine):

```bash
cd ../server
RATATOSKR_DATA_DIR=/tmp/ratatoskr-test RATATOSKR_ARGON2_MEMORY_KIB=65536 \
    uvicorn app.main:app --host 127.0.0.1 --port 8765
```

The desktop app connects to `127.0.0.1:8765` directly. For an Android
phone plugged in over USB (with USB debugging on), `adb reverse tcp:8765
tcp:8765` makes the same address work on the phone, without exposing the
server on your network.

## Linux packaging

Compose Desktop's `createDistributable` task (jpackage) builds a
self-contained app image: launcher + app jars + a jlinked Java runtime.
`desktop/packaging/linux/build-packages.sh` wraps that one image three ways:

| File | For | Tool |
|---|---|---|
| `Ratatoskr-x86_64.AppImage` | any distro, no install | [appimagetool](https://github.com/AppImage/appimagetool) |
| `ratatoskr_<ver>_amd64.deb` | Debian/Ubuntu | [nfpm](https://nfpm.goreleaser.com) |
| `ratatoskr-<ver>-1.x86_64.rpm` | Fedora/RHEL | nfpm |

jpackage can build `.deb`/`.rpm` itself, but its generated `.desktop` file
can't carry the app ID, `StartupWMClass` or AppStream metadata, so we build
the packages from the app image instead.

```bash
./gradlew :desktop:createDistributable
desktop/packaging/linux/build-packages.sh     # needs nfpm + appimagetool on PATH
ls desktop/build/linux-packages/
```

### Desktop integration

All in `desktop/packaging/linux/`, installed by the `.deb`/`.rpm` (and
embedded in the AppImage):

- **App ID** `io.github.curtis04ben.Ratatoskr`, the reverse-DNS of the
  GitHub Pages domain the project controls. It names the `.desktop` file,
  the icon and the AppStream metadata. Don't change it once released:
  launchers, pinned favourites and software centres key on it.
- **`.desktop` entry** -> `/usr/share/applications/`. `StartupWMClass`
  must equal the window's X11 `WM_CLASS`, which AWT derives from the main
  class (`com-ratatoskr-desktop-MainKt`). If `mainClass` is renamed, update
  it, or KDE/GNOME will show the running window with a generic icon.
- **Icons** -> `/usr/share/icons/hicolor/{48,128,256,512}/apps/`, from `desktop/icons/`.
- **AppStream** metainfo -> `/usr/share/metainfo/` (Discover/GNOME Software).

Install layout: the app in `/opt/ratatoskr/`, `/usr/bin/ratatoskr` a
symlink to its launcher.

The desktop app remembers the last server (`DesktopSessionStore`, a
one-line `settings.properties` in `~/.config/ratatoskr/`, `%APPDATA%\Ratatoskr`
or `~/Library/Application Support/Ratatoskr`), so it opens on Unlock for
that server. It deliberately never writes the session token: keeping you
signed in across launches needs an encrypted store, which on Linux means
libsecret (the system keyring), the desktop counterpart of Android's
`KeystoreSessionStore`, plugged into the same shared `SessionStore`
interface. Until then, the master password is asked for on each launch.

## Versioning and releases

> **Planned change:** releases will move to two kinds: **Desktop**
> (`desktop-vX.Y.Z`: Linux, Windows, macOS, starting at 1.0.0) and
> **Mobile** (`mobile-vX.Y.Z`: Android, later iOS), each with its own
> version. The design is in the "Release plan" section of
> [`docs/development-plan.md`](../docs/development-plan.md#release-plan-desktop-and-mobile-releases-decided).
> What follows describes the current separate `linux-v*`/`android-v*` releases.

The desktop version lives in one place: `ratatoskrDesktopVersion` in
`gradle.properties`. Gradle's `packageVersion` and the package filenames
all read it.

Releases use their own tags (`linux-vX.Y.Z`), separate from the server, so
each component gets its own GitHub Release with only its own files:

```bash
# 1. bump ratatoskrDesktopVersion in clients/gradle.properties, commit, push
# 2. tag that commit with the same version
git tag linux-vX.Y.Z
git push origin linux-vX.Y.Z
```

The tag triggers `.github/workflows/linux-client-release.yml`, which runs
the tests, builds all three packages, and publishes them with a
`SHA256SUMS` file as the release "Ratatoskr Linux client vX.Y.Z". It
refuses to release if the tag doesn't match `ratatoskrDesktopVersion`.
Running the workflow manually from the Actions tab builds the packages as
a workflow artifact without releasing anything.

## Android

`android/` is a thin Android app around the same shared screens. What's
Android-specific:

- **`MainActivity`**: entry point. Sets `FLAG_SECURE` (no screenshots,
  screen recording or recent-apps preview of the vault) and draws
  edge-to-edge with content padded clear of system bars and the keyboard.
- **`KeystoreSessionStore`**: remembers the server and session token
  across launches, since Android kills backgrounded apps freely. The token
  is AES-256-GCM encrypted under a non-exportable Android Keystore key;
  the master password is never stored. (`EncryptedSharedPreferences` is
  deprecated, so this uses the Keystore directly.) Excluded from backups.
- **`AndroidFiles`**: CSV import/export via the system file picker, so
  no storage permission is needed.
- **Manifest**: `INTERNET` is the only permission. Plain `http://` is
  allowed because home servers usually don't have TLS; the connect screen
  warns when the address isn't `https://`.
- **Autofill** (`autofill/`): Ratatoskr as the phone's autofill service,
  like Samsung Pass. Turn it on from the vault's ⋮ menu, **Autofill
  settings** (or Settings, Passwords and autofill). On a login form in
  another app or a browser it suggests matching entries, or "Unlock
  Ratatoskr" while locked; after a sign-in with a password it doesn't
  have, it offers to save it (or update the entry whose password changed).
  - *Matching:* web pages match an entry's URL by site (`mail.google.com`
    matches `google.com`), but only in known browsers (`TRUSTED_BROWSERS`),
    since an app could read what's filled into its own WebView. Apps match
    entries saved from them (URL `androidapp://<package>`, the convention
    other managers use) or an entry picked for them once via "Search
    Ratatoskr…"; those pairings are kept on the device. Nothing is matched
    by name guessing.
  - *Session:* uses the app's saved server and encrypted token, so
    unlocking in either place unlocks both. Entries are fetched per request
    and never cached. Fill needs the server reachable.
  - *Chrome* only hands forms to another service once **Autofill using
    another service** is on in Chrome's settings. Firefox works directly.
  - Tests: `./gradlew :android:testDebugUnitTest` (matching rules).

App ID `io.github.curtis04ben.ratatoskr` (lowercase by Android
convention). Like the desktop ID, it's permanent once released. The
launcher name is "Ratatoskr Self-Hosted", to tell it apart from the
standalone Ratatoskr app (a separate project, `...ratatoskr.local`); the
autofill service ignores both apps' own screens.

**Icons** everywhere (Android launcher and in-app logo, Linux icons, the
web UI's favicon and header) are generated from
`shared/assets/ratatoskr-self-hosted.jpg` (1024 px) with ImageMagick. The
Android adaptive icon is the whole image on its own background colour
(`#151618`), plus a monochrome layer of just the artwork for themed icons. Minimum
Android 8.0 (API 26), which is where the Autofill Framework starts.

### Building and running

Needs the Android SDK (`ANDROID_HOME`, or `sdk.dir` in
`clients/local.properties`, which is git-ignored).

```bash
./gradlew :android:installDebug     # build + install on a connected phone/emulator
./gradlew :android:assembleRelease  # release APK (signed only if the env vars below are set)
```

Debug builds install as `io.github.curtis04ben.ratatoskr.debug`, alongside
a release install rather than replacing it.

### Signing

Android only accepts an update signed with the same key as the installed
app, so the release key is permanent: **if it's lost, every user has to
uninstall and reinstall** (losing their saved server). Back it up somewhere
safe outside this machine.

Create it once, on your own machine (it prompts for a password):

```bash
keytool -genkeypair -v -keystore ~/ratatoskr-release.jks -alias ratatoskr \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Ratatoskr"
base64 -w0 ~/ratatoskr-release.jks > ~/ratatoskr-release.jks.b64
```

Then add four repository secrets in GitHub (Settings -> Secrets and
variables -> Actions): `RATATOSKR_KEYSTORE_BASE64` (the `.b64` file's
contents), `RATATOSKR_KEYSTORE_PASSWORD`, `RATATOSKR_KEY_ALIAS`
(`ratatoskr`), and `RATATOSKR_KEY_PASSWORD` (the same password, since
PKCS12 keystores use one). Delete the `.b64` file afterwards. The keystore
never goes in the repo.

To sign locally, set the same variables, with `RATATOSKR_KEYSTORE_FILE`
pointing at the `.jks` instead of the base64 one.

### Releases

Versioned separately as `ratatoskrAndroidVersion` in `gradle.properties`;
`versionCode` is derived from it (`1.2.3` -> `10203`), so it always
increases. Bump, commit, push, then:

```bash
git tag android-vX.Y.Z
git push origin android-vX.Y.Z
```

`.github/workflows/android-client-release.yml` tests, builds and signs the
APK, and publishes `Ratatoskr-<version>.apk` + `SHA256SUMS` as the release
"Ratatoskr Android vX.Y.Z", with install steps and the signing
certificate's fingerprint in the notes. It won't release unsigned.

Before building, it checks the signing secrets against the keystore and
fails with a message naming the one that's wrong (including a checksum of
the decoded keystore to compare with `sha256sum ~/ratatoskr-release.jks`).
Any Gradle failure's "What went wrong" text is copied onto the run's
summary page, so you don't need to dig through the log.
