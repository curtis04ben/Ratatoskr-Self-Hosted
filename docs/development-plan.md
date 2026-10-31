# Ratatoskr (Self-Hosted) development plan

This documents the architecture decisions behind the multi-client Ratatoskr
(Self-Hosted) project and the planned approach for each native client. It's meant as
durable context for whoever (human or AI assistant) picks up client work
next. It's not a rigid spec, more a record of *why* things are shaped the way
they are, so that reasoning doesn't have to be reconstructed from scratch
each time.

## Picking this back up

**Where things stand** (update this list whenever it changes):

| Component | Version | Tag / release | Notes |
|---|---|---|---|
| Server + web UI | 2.4.0 | none (deployed from source) | `server/app/main.py` holds the version |
| Linux app | 0.1.4 | `linux-v0.1.4` | `ratatoskrDesktopVersion` in `clients/gradle.properties` |
| Android app | 0.2.0 | `android-v0.2.0` | `ratatoskrAndroidVersion`; release key + 4 GitHub secrets in place. 0.2.0 adds Autofill |
| Windows / macOS | not yet | not yet | Phases 4/5 below: packaging on the same `desktop/` module |
| Android biometric unlock | not yet | not yet | Phase 3.1 below |
| Android Autofill | 0.2.0 | with the app | Phase 3.2 below: stages 1 to 3 done |
| Standalone app (vault on the phone) | moved | separate repo | Now its own project, "Ratatoskr"; see Phase 3.3 below |

Both native apps cover: connect (server remembered), setup, unlock,
accept-invite, entry list/search/create/edit/delete, live TOTP, password
generator, CSV import/export, change master password, lock. Not in the
apps yet (web UI only): sharing, the admin Users panel, factory reset.

**Decisions already made by the project owner** (don't re-ask; revisit
only if something makes one unworkable, and say so):

- **Two products (2026-10-31).** This repo is **Ratatoskr (Self-Hosted)**:
  server, web UI, desktop and Android clients, **free**, on GitHub
  Releases. The standalone phone app is **Ratatoskr**, a separate repo,
  headed for the Google Play Store. They no longer share code; each
  repo owns its copy.
- **Logo:** `shared/assets/ratatoskr-self-hosted.jpg` (the squirrel over
  a server), used for every icon here: web favicon and header, Linux
  icons, Android launcher. The original squirrel SVG belongs to
  standalone Ratatoskr. The Android app's launcher name is "Ratatoskr
  Self-Hosted", so the two are easy to tell apart on one phone.

- **Two releases: Desktop and Mobile.** A `desktop-vX.Y.Z` tag builds
  Linux (`.rpm`, `.deb`, AppImage), Windows (`.exe`) and macOS (`.pkg`)
  into one GitHub Release, "Ratatoskr Desktop"; a `mobile-vX.Y.Z` tag
  builds Android (`.apk`), and later iOS, into "Ratatoskr Mobile". Each has
  its own version number, shared by the platforms inside it. This replaces
  the per-platform `linux-v*` / `android-v*` releases. (An earlier plan for
  one release covering everything was dropped in favour of this split.)
  See "Release plan" below.
- **macOS: Apple silicon (arm64) only.** Intel Macs are out of scope
  (Apple no longer supports them). The owner has both kinds of Mac for
  testing but develops and tests for Apple silicon.
- **Windows is tested in a VM** (the owner doesn't use Windows; spare
  hardware is available as a fallback).
- **Biometric unlock** (fingerprint/face) is wanted on Android first,
  then Touch ID on macOS and Face ID/Touch ID on iOS. See Phase 3.1 and
  the macOS/iOS phases.
- Apps are versioned independently of the server; pushing straight to
  `main` is fine; no licence file for now (owner's choice).
- The owner's personal email must not appear in shipped artifacts or
  metadata; use `curtis04ben@users.noreply.github.com` where an email is
  required (e.g. the Debian maintainer field).

**Suggested order of work** (the owner may reorder):

0. ~~**Android standalone app** (Phase 3.3).~~ Built, then split out on
   2026-10-31 into its own project, **Ratatoskr** (separate repo), which
   is headed for the Play Store. Nothing more to do for it here.
1. **Android biometric unlock** (Phase 3.1): self-contained, testable on
   the owner's phone, and the Autofill unlock flow reuses it.
2. **Desktop/Mobile release pipelines** (Release plan): turn the Linux
   workflow into `desktop-release.yml` and cut `desktop-v1.0.0` (Linux
   only at first), and turn the Android workflow into `mobile-release.yml`
   for the next Android release, e.g. the one with biometrics. Add the
   Windows and macOS jobs to the desktop workflow as those phases land.
3. **Windows** (Phase 4), tested in a VM.
4. **macOS** (Phase 5), including the signing decision and Touch ID.
5. ~~**Android Autofill** (Phase 3.2).~~ Stages 1 to 3 done in Android 0.2.0;
   inline keyboard suggestions (stage 4) and biometric unlock in the
   autofill unlock screen remain.
6. **Desktop "stay signed in"** via each OS's keyring (Desktop session
   storage).
7. **Sharing and the admin Users panel in the apps** (Phase 2 fast-follows).
8. **iOS** (Phase 6).

**Development machine setup** (a Fedora KDE box so far):

- **JDK 21 with jmods.** Everyday builds work with Fedora's
  `java-21-openjdk-devel`, but *packaging* (`createDistributable`, jlink)
  also needs `sudo dnf install java-21-openjdk-jmods`, or point
  `JAVA_HOME` at a Temurin 21 JDK (which bundles jmods). Without it:
  "jlink ... this runtime image does not contain jmods directory".
- **Android SDK** lives in `~/Android/Sdk`, with `sdk.dir` in
  `clients/local.properties` (git-ignored). The `sdkmanager` wrapper in
  current command-line tools hands off to a new `android` CLI and can hang
  silently, leaving orphaned `android-cli` processes that hold a lock; use
  `~/Android/Sdk/cmdline-tools/latest/bin/android sdk install <package>`
  directly (package names use `/`, e.g. `platforms/android-37.0`).
- **The Android emulator segfaulted** on this machine's kernel (7.1) with
  every GPU mode tried. Test on a real phone instead: USB debugging on,
  `adb reverse tcp:8765 tcp:8765` to a scratch server (recipe in
  `clients/README.md`). Debug builds install as
  `io.github.curtis04ben.ratatoskr.debug`, alongside the real app.
  `adb shell uiautomator dump` lists on-screen text and bounds, which
  makes scripted tapping reliable (tap by text, not by guessed pixels).
- **Web UI** changes can be checked headlessly with Playwright + Firefox
  against a scratch server (`pip install playwright && playwright install
  firefox` in a throwaway venv).
- The Linux packaging tools (nfpm, appimagetool) are only needed to build
  packages locally; CI downloads pinned, checksum-verified copies itself.

- **Windows VM for testing:** this machine has usable KVM (`/dev/kvm`),
  so virt-manager/QEMU works. Windows 11 needs a virtual TPM (`swtpm`)
  and UEFI/Secure Boot in the VM, or use Microsoft's Windows 11
  development VM images, which are time-limited but preconfigured.
  Install builds from the CI artifacts (see Release plan).
- **Macs** are separate machines: test by downloading CI artifacts onto
  the Apple silicon Mac. Anything that needs Xcode (the Touch ID helper,
  `.icns` generation, iOS) runs on the macOS CI runner or that Mac.

**How work gets shipped today** (until the Desktop/Mobile pipelines replace
it): commit to `main`, bump `ratatoskrDesktopVersion` /
`ratatoskrAndroidVersion` in `clients/gradle.properties`, then push a
`linux-vX.Y.Z` / `android-vX.Y.Z` tag. The workflow refuses to release if
the tag doesn't match the version. Details in `clients/README.md`.

**Things that have caught us out before** (check these first when
something breaks):

- XML comments in Android resources can't contain `--`.
- GitHub Actions: a variable written to `$GITHUB_ENV` applies to *every
  later step*. The Android signing step exports `RATATOSKR_KEYSTORE_FILE`,
  and Gradle then needs all four signing variables to configure at all,
  which is why they're set job-wide.
- A command piped through `tee` hides its exit code unless `set -o
  pipefail`; that once let a missing `apksigner` produce a blank
  fingerprint in release notes.
- Workflow failures: the Android workflow copies Gradle's "What went
  wrong" into an error annotation, readable from the public API
  (`/repos/.../check-runs/<job id>/annotations`) without a token. There's
  no `gh` CLI or GitHub token on the dev machine; `git push` works, but
  deleting releases or setting secrets needs the owner in the web UI.
- The web UI's `api()` treats any 401 as "session expired" and logs out;
  pass `expireOn401: false` for calls where 401 means "wrong password".
- `restoreSession()` reconnects through `connectToServer()`, which
  re-saves the server *without* a token; it then re-saves the loaded
  session, so tokens survive repeated restores. Keep that in mind when
  touching either function.
- Sessions live in the server's memory (each holds the user's unwrapped
  private key), so restarting the server signs everyone out; a saved
  token then fails and the app falls back to Unlock, which is expected.

## Repository shape

```
Ratatoskr/
├── server/          Python/FastAPI backend + web UI (the original project)
├── clients/         Kotlin Multiplatform + Compose Multiplatform monorepo
│   ├── shared/       portable API client, models, TOTP, app state, UI screens
│   ├── desktop/      JVM/Compose Desktop entry point, builds Linux, Windows, AND macOS
│   └── android/      Android app module (AGP 9 requires it separate from shared/)
├── shared/assets/    master logo (ratatoskr-self-hosted.jpg, 1024 px) that every icon derives from
├── docs/             this file, plus anything else durable
└── .github/workflows/  tag-triggered release builds (linux-v*, android-v* today;
                        desktop-v* and mobile-v* planned, see Release plan)
```

**Monorepo, not polyrepo.** Chosen specifically because development happens
solo with AI assistants: a client change and the API change it depends on
can land in one PR, and an assistant editing a client can read the real
`server/app/schemas.py` directly instead of working from a secondhand
description. Revisit only if a specific client's build tooling genuinely
conflicts with the others (CI minutes, SDK bloat), not preemptively.

**Independent versioning per component**, not one repo-wide tag. The
server is mature; a first client starts at 0.1.0. Tags in use:
`linux-vX.Y.Z` and `android-vX.Y.Z`, each producing its own GitHub Release
(versions in `clients/gradle.properties`). The server is still deployed
from source; a `server-vX.Y.Z` tag would follow the same pattern if it
ever gets released builds (e.g. a published Docker image).

**`server/` nesting** happened together with adding `clients/`, not before.
Moving it earlier would have broken every already-documented deployment
path (TrueNAS, CasaOS) for zero benefit until there was a second thing in
the repo to justify the split.

## Client technology: Compose Multiplatform

Chosen over wrapping the existing web UI (Tauri/Electron) because the
whole point of a *native* client, as the person who owns this project put
it, is defeated by wrapping a web view. Chosen over Flutter/native-per-
platform because Compose Multiplatform is genuinely first-class on both
Android (the very next phase after Linux) and Desktop (Linux/Windows/
macOS) via the JVM, meaning most UI code gets written once and reused,
rather than five platform efforts happening independently.

**Consequence worth being explicit about**: `clients/desktop` is *one*
module that produces installers for Linux, Windows, and macOS, not three
separate client efforts. Phase 2 ("build the Linux client") is really
"build the desktop app"; Phases 4 and 5 are mostly packaging and
platform-glue work on an app that already exists, not new app-building
phases. Treating them as separate would throw away the exact benefit that
justified this framework choice.

## What's genuinely shared vs. what isn't

**Shared (`clients/shared`, commonMain)**: the API client, all data
models, TOTP generation, app/session state, and every screen's UI. This is
the bulk of the app.

**Not shared, by necessity** (`expect`/`actual` platform code, added as
each phase needs it): secure credential storage (Keystore on Android,
Keychain on iOS/macOS, Credential Manager/DPAPI on Windows, libsecret on
Linux), and each platform's own app entry point / window chrome.

**API definitions**: not a hand-maintained shared schema file. The
server's FastAPI already generates a live OpenAPI spec at `/openapi.json`.
That's the real contract. `clients/shared`'s Kotlin models are currently
hand-written against the real `server/app/schemas.py` (accurate as of
this writing, small enough surface that hand-matching is less overhead
than a codegen dependency for one client). Worth switching to generating
Kotlin bindings from the live OpenAPI spec once there are several clients
all needing to stay in sync. It's not worth it yet for one.

## Client-side crypto: there currently isn't any (beyond TOTP)

Worth stating plainly since it's easy to assume otherwise given how much
cryptographic engineering went into the server: the server derives the
Argon2id key and decrypts entries server-side; the API returns
already-decrypted data to an authenticated session. A native client is
therefore a thin REST client, same as the web UI. It does not need to
reimplement Argon2id/X25519 sealing in Kotlin.

The one piece of real crypto in `clients/shared` is `Totp.kt`, a
hand-written, pure-Kotlin RFC 6238 implementation, deliberately not using
any platform crypto API (`javax.crypto` on JVM, CryptoKit on iOS, etc.),
so it produces identical output on every target without per-platform
wiring. This is the third independent implementation of the same
algorithm in this project (server: Python, web UI: JavaScript, clients:
Kotlin). All three were checked against the official RFC 6238 Appendix B
test vectors and against each other during development.

**The standalone app is different:** Ratatoskr (its own repo now, see
Phase 3.3) has no server, so it does its own vault crypto (Argon2id,
AES-GCM) on the phone. The clients in this repo stay thin.

If true end-to-end zero-knowledge (server never sees the master password,
only pre-encrypted blobs) is ever wanted, that's a materially bigger
redesign than anything in this document, deserving its own deliberate
decision, not something to back into via a client architecture choice.

## Per-client plan

### Phase 1: Server prep (done alongside the `clients/` scaffold)

- `server/` move, done.
- Open item, not yet decided: session lifetime for native clients. The
  current 30-minute idle timeout matches a browser-tab mental model; a
  native app likely wants "stay signed in" longer. Probably a longer
  configurable timeout for native sessions rather than a full redesign.
- CORS (`RATATOSKR_EXTRA_ORIGINS`) is irrelevant to native clients, as it's
  a browser-only enforcement mechanism. Nothing to add there for native
  clients specifically.

### Phase 2: Linux (released; open items below)

MVP scope, deliberately smaller than full web-UI parity:

- ✅ Server connect screen (enter address, test connectivity)
- ✅ Setup (first-run admin creation)
- ✅ Unlock
- ✅ Accept-invite (join with a code from an admin)
- ✅ Entry list with search
- ✅ Entry create/edit/delete
- ✅ TOTP: live code + countdown, toggleable per entry
- ✅ Password generator
- ✅ CSV import/export
- ✅ Change master password (header ⋮ menu)
- ✅ Server remembered between launches (`DesktopSessionStore`: address
  only, in the per-user config dir; the token is never written to disk)
- ⬜ Stay signed in across launches: needs the token in the system
  keyring (libsecret on Linux). See "Desktop session storage" under
  Phase 4, which covers all three desktop OSes
- ⬜ Clean handling of every disconnect/error edge case (some exists via
  `RatatoskrConnectionException`/`RatatoskrApiException`, not exhaustively
  tested against real network conditions)

**Deliberately deferred as fast-follows, not oversights**: sharing
(entries can be shared between users on the server, but there's no Share
dialog in the clients yet) and the admin users panel (invite creation,
role changes, removal). Both exist in the web UI and the REST API
(`RatatoskrApiClient` already has `listShares`/`shareEntry`/
`unshareEntry` and `listUsers`/`inviteUser`/`updateUserRole`/`deleteUser`),
so they're UI work in `clients/shared`, benefiting every app at once.

Packaging (done): Compose Desktop's `createDistributable` (jpackage)
builds a self-contained app image, and `clients/desktop/packaging/linux/`
wraps it as AppImage (appimagetool) plus `.deb`/`.rpm` (nfpm), with a
Freedesktop `.desktop` entry, icons and AppStream metadata under app ID
`io.github.curtis04ben.Ratatoskr`. jpackage's own `.deb`/`.rpm` output was
skipped because its generated `.desktop` file can't carry the app ID or
`StartupWMClass`. Pushing a `linux-vX.Y.Z` tag publishes a GitHub Release
via `.github/workflows/linux-client-release.yml`. Details: `clients/README.md`.

### Phase 3: Android (released; biometrics are 3.1, Autofill 3.2 done)

Mostly reuse: `clients/shared`'s screens carry over directly. `:shared`
gained an Android target (AGP's KMP library plugin, OkHttp engine) and
`clients/android` is the app module. AGP 9 requires the app in its own
module rather than an Android target on the KMP module. Real new work:

- ✅ Session storage: `SessionStore` in commonMain (optional, like
  `PlatformFiles`; desktop's stores the server address only), implemented on Android as
  `KeystoreSessionStore`, with the token AES-GCM-encrypted under a
  non-exportable Keystore key. Not `EncryptedSharedPreferences`: that
  library was deprecated in 2025. Server address + token survive process
  death; an expired token lands on Unlock for the same server.
- ✅ Manifest: `INTERNET` only; cleartext allowed (homelab HTTP over
  Tailscale) with an in-app warning on non-HTTPS addresses; no backups of
  the session; `FLAG_SECURE`.
- ✅ Shared UI made width-adaptive for phones (max-width columns, scrolling
  forms, compact vault toolbar), with no visual change on desktop.
- ✅ CSV import/export via the Storage Access Framework.
- ✅ Signed APK released via `android-vX.Y.Z` tags
  (`.github/workflows/android-client-release.yml`).

minSdk 26 (Android 8.0) was chosen because that's where the Autofill
Framework starts, so the stretch goal below doesn't force a bump.

### Phase 3.1: Android biometric unlock (next up)

Goal: unlock with fingerprint or face instead of typing the master
password, on the Unlock screen. The same idea later applies to Touch ID
on macOS (Phase 5) and Face ID/Touch ID on iOS (Phase 6); the shared
interface below is designed for all three.

**Why it isn't trivial:** the server derives the key that unwraps the
user's private key from the *master password*, at `POST /auth/unlock`.
There's nothing on the device a fingerprint can unlock by itself, and a
session token alone doesn't help: tokens expire after 30 idle minutes
(`RATATOSKR_SESSION_TIMEOUT`) and whenever the server restarts (sessions
are in memory). Biometric unlock therefore has to produce something the
server accepts at `/auth/unlock`.

**Chosen design for v1: the master password, sealed by a
biometric-bound Keystore key.**

- *Opt-in, after a successful password unlock.* Offer once: "Unlock with
  fingerprint or face next time?" (also a toggle in the header ⋮ menu).
  Only offer when `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`
  returns `BIOMETRIC_SUCCESS`.
- *Enrolment:* create a Keystore AES-256-GCM key (separate alias from the
  session key, e.g. `ratatoskr-biometric`) with
  `setUserAuthenticationRequired(true)`,
  `setInvalidatedByBiometricEnrollment(true)`, and on API 30+
  `setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)`
  (API 26–29: `setUserAuthenticationValidityDurationSeconds(-1)`, which
  means "every use"). Every use of the key needs a fresh biometric, *including
  encryption*, so enrolment shows one `BiometricPrompt` with a
  `CryptoObject` wrapping an ENCRYPT cipher, then encrypts the master
  password. Store the ciphertext + IV, the username and the server URL it
  belongs to in private SharedPreferences (already excluded from backups).
  Optionally request StrongBox (`setIsStrongBoxBacked`) and fall back if
  it's unavailable.
- *Unlock:* if an enrolment exists for the saved server, the Unlock screen
  shows "Unlock with fingerprint" (and may open the prompt automatically
  on launch). `BiometricPrompt` + a DECRYPT `CryptoObject` → decrypt →
  the normal `AppState.unlock(username, password)`. The username is
  stored, so nothing needs typing.
- *Invalidation, each of which deletes the enrolment and falls back to
  the password form with a short explanation:*
  - `KeyPermanentlyInvalidatedException` when initialising the cipher (a
    new fingerprint or face was enrolled, or biometrics were removed);
  - `/auth/unlock` returns 401 (the password was changed on another device);
  - Change server, factory reset, or the user turning the toggle off.
  - After an in-app **Change master password**, delete it and offer
    enrolment again (re-encrypting needs a new biometric prompt anyway).
  - **Lock** must *not* remove it: locking and then unlocking with a
    fingerprint is the point.
- *The honest trade-off, to say in the opt-in text:* the master password
  is stored on the phone, encrypted under a hardware-backed key that the
  OS only releases after a strong biometric match. That's the same
  principle as other managers' "unlock with biometrics", which store the
  vault key; here the server holds the vault key, so the password is the
  equivalent secret. Keep it opt-in. Kotlin `String`s can't be wiped from
  memory, so drop references promptly; don't pretend otherwise.

**Considered for later, not v1: device keys on the server.** The server
would wrap the user's private key a second time under a per-device
secret held in the biometric Keystore, so the device never stores the
password. It's cleaner, but a real crypto and API change (a new
endpoint, per-device wrapped keys, a revoke list in the web UI). It
overlaps the Phase 1 open item on longer native sessions, so design them
together if it's ever wanted.

**Implementation notes:**

- Library: `androidx.biometric:biometric` covers API 26+ (the framework
  `BiometricPrompt` alone starts at 28). Check the current version when
  starting; the stable line has lagged behind its alphas for a while.
- `BiometricPrompt` needs a `FragmentActivity`. `MainActivity` is a
  `ComponentActivity` today; switch it to `androidx.fragment.app.FragmentActivity`
  (add `androidx.fragment:fragment`), since AppCompat isn't needed.
- Shared code: add a `BiometricUnlock` interface in
  `clients/shared/.../platform/` alongside `SessionStore`, supplied by
  each platform's entry point and optional (null = feature hidden):
  - `fun isAvailable(): Boolean`: hardware present and enrolled;
  - `fun enrolledFor(serverUrl: String): String?`: the username, if set up;
  - `suspend fun enrol(serverUrl: String, username: String, password: String): Boolean`:
    shows the prompt;
  - `suspend fun unlock(serverUrl: String): Pair<String, String>?`: shows
    the prompt, returns username + password, or null if cancelled or failed;
  - `fun clear()`.

  Suspend functions wrap the prompt callbacks
  (`suspendCancellableCoroutine`). `AppState` and `UnlockScreen` use it;
  macOS and iOS implement the same interface later.
- Prompt text: title "Unlock Ratatoskr", subtitle with the username and
  server, negative button "Use master password".
- Allowed authenticators: `BIOMETRIC_STRONG` only, so a crypto-backed
  prompt is possible. Device PIN/pattern (`DEVICE_CREDENTIAL`) is
  deliberately excluded: a phone PIN is usually weaker than the master
  password.

**Testing:** on the owner's phone (Galaxy A55 has fingerprint + face;
face on many Samsungs is *not* BIOMETRIC_STRONG, so expect fingerprint
only, and check what `canAuthenticate` reports). Cases:
- enrol, then lock and unlock with a fingerprint;
- kill and relaunch, then unlock with a fingerprint;
- cancel the prompt (falls back to the password form);
- enrol a new fingerprint in Settings (invalidates, falls back);
- change the password on the web UI (the next biometric unlock gets a 401,
  clears the enrolment, and asks for the password);
- Change server clears it.

`adb` can't fake a fingerprint on a real device, so a person has to
touch the sensor; script everything around that.

### Phase 3.2: Android Autofill (stages 1 to 3 done, Android 0.2.0)

**What was built** (`clients/android/.../autofill/`, summarised in
`clients/README.md`): the service, field detection, locked/unlocked
responses, website matching (trusted browsers only), app matching via
`androidapp://<package>` URLs on entries saved from an app plus on-device
pairings from "Search Ratatoskr…" (the on-device option below, so no
server schema change), and save/update requests. Tested on the owner's
Galaxy A55 (Android 16) with a sample login app and Firefox. Still open:
stage 4 (inline suggestions), offering Phase 3.1's biometric unlock in
`AutofillActivity` once it exists, and Digital Asset Links.

The original plan, kept for reference:


Goal: Ratatoskr as the phone's autofill service, filling logins in other
apps and (via the browser) websites. This is a genuinely different
integration point from the separate browser-extension project. It's a
sizeable piece of work, so the plan below is staged.

**Moving parts** (all in `clients/android`):

- An `AutofillService` subclass, declared in the manifest with
  `android:permission="android.permission.BIND_AUTOFILL_SERVICE"`, an
  intent filter for `android.service.autofill.AutofillService`, and a
  `<meta-data android:name="android.autofill">` XML naming a settings
  activity. The user then picks it in Settings → Passwords/Autofill
  service (wording varies by manufacturer; on Samsung it's under General
  management).
- `onFillRequest`: walk the `AssistStructure` to find the username and
  password fields (`autofillHints` first, then `inputType` password
  flags, then view id/hint text heuristics), and read the target: the
  requesting app's package name, or `webDomain` when the fields belong to
  a web page in a browser.
- Respond with one `Dataset` per matching entry. **If the vault is
  locked** (no valid token), respond with an authentication `IntentSender`
  (`FillResponse.Builder.setAuthentication`) that opens an unlock activity
  and returns the datasets once unlocked, so fill never happens without
  an unlocked session. That activity should offer Phase 3.1's biometric
  unlock when enrolled: a fingerprint is what makes autofill pleasant.
- `onSaveRequest` (stage 2): offer to save new credentials as an entry.

**Reuse:** the service can't use `AppState` (it's UI state); it should
use `RatatoskrApiClient` plus `KeystoreSessionStore` directly: load the
saved server and token, call `listEntries()`, and match. Keep decrypted
entries in memory only, never cached on disk. The server does the
decryption, so fill needs the server reachable.

**The hard problem: matching an app to an entry.** Entries have a `url`
but nothing that names an Android app.

1. Websites (stage 1): match `webDomain` against the host of each entry's
   `url`. Straightforward.
2. Native apps: options, from simplest up:
   - Manual: when nothing matches, the response offers "Search Ratatoskr…",
     which opens a picker; remember the chosen package → entry pairing.
     Where to remember it is a decision: on-device only, or a new field on
     the entry (a server schema change to `EntryIn`/`EntryOut`, which the
     server README says is straightforward, and would sync across
     devices).
   - Digital Asset Links: verify an app's package ↔ website association
     (what Google Password Manager does). Most correct, most work; worth
     it only after the manual flow is proven.

**Suggested stages:** (1) service + locked/unlocked flow + website domain
matching; (2) manual package pairing; (3) save requests; (4) inline
suggestions in the keyboard (Android 11+), optional polish.

**Testing:** needs the real phone (the emulator doesn't run on the dev
machine), with Ratatoskr selected as the autofill service. Chrome only
passes web forms to third-party autofill services once "Autofill using
another service" is enabled in its settings. Test against a sample login
app and a few real sites.

**Play Store caveat** (for later): autofill services get extra review
scrutiny on Play. GitHub Releases distribution isn't affected.

Distribution: GitHub Releases APK first. Play Store is a later, deliberate
decision. A password manager requesting autofill/accessibility
permissions gets real scrutiny in Play's review process; not a v1
assumption.

### Phase 3.3: Android standalone app (moved to its own repo)

Built here as a second Android edition, "Ratatoskr (Local)", on
2026-10-31, then split into its own project, **Ratatoskr**: the vault is
on the phone (Argon2id-wrapped vault key, AES-GCM per entry), no server
and no internet permission, with encrypted backup files. Its plan,
including the Google Play checklist, is in that repo's
`docs/development-plan.md`.

What stayed here from that work, because it improves this app too:
`VaultBackend`/`RemoteVaultBackend` (`clients/shared/.../backend/`), so
`AppState` and the screens don't call the HTTP client directly; a 401 on
a vault call returns to Unlock instead of showing an error over a dead
session; dialogs close when leaving the vault; and autofill ignores both
apps' own screens (`isRatatoskrApp`), so neither offers to save the
other's master password.

### Release plan: Desktop and Mobile releases (decided)

Clients ship as two kinds of GitHub Release. The server isn't in either:
it's deployed from source. If it ever gets published builds (e.g. a
Docker image), tag those `server-vX.Y.Z`.

| Release | Tag | Version property | Workflow | Replaces |
|---|---|---|---|---|
| Ratatoskr Desktop vX.Y.Z | `desktop-vX.Y.Z` | `ratatoskrDesktopVersion` (exists) | `desktop-release.yml` | `linux-client-release.yml` |
| Ratatoskr Mobile vX.Y.Z | `mobile-vX.Y.Z` | `ratatoskrMobileVersion` (rename of `ratatoskrAndroidVersion`) | `mobile-release.yml` | `android-client-release.yml` |

**Desktop release assets:**

| Platform | File | Built by |
|---|---|---|
| Linux (Fedora/RHEL) | `ratatoskr-X.Y.Z-1.x86_64.rpm` | `linux` job, `ubuntu-24.04`: `createDistributable` + `desktop/packaging/linux/build-packages.sh` (existing) |
| Linux (Debian/Ubuntu) | `ratatoskr_X.Y.Z_amd64.deb` | same job |
| Linux (any) | `Ratatoskr-x86_64.AppImage` | same job |
| Windows | `Ratatoskr-X.Y.Z.exe` (installer) | `windows` job, `windows-latest`: `:desktop:packageExe` (Phase 4) |
| macOS (Apple silicon) | `Ratatoskr-X.Y.Z-arm64.pkg` | `macos` job, `macos-latest` (arm64): `:desktop:packagePkg` (Phase 5) |
| All | `SHA256SUMS` | `release` job |

The macOS file name says `-arm64` so nobody mistakes it for an Intel
build. The owner asked for a `.pkg` (installs Ratatoskr.app into
/Applications); a `.dmg` (`packageDmg`) could be added alongside later.

**Mobile release assets:**

| Platform | File | Built by |
|---|---|---|
| Android | `Ratatoskr-X.Y.Z.apk` (signed) | `android` job, `ubuntu-24.04`: `:android:assembleRelease` (existing) |
| iOS | *no file*: iOS can't sideload a downloaded app | `ios` job, `macos-latest` (Phase 6): builds, and uploads to TestFlight if that route is chosen; the release notes then link to TestFlight |
| All | `SHA256SUMS` | `release` job |

**Versions:**

- **Desktop:** keep `ratatoskrDesktopVersion`. Make the first
  `desktop-v` release **1.0.0**: macOS requires the bundle version's
  major part to be > 0, so 0.x can't be used there, and 1.0.0 is the
  natural "Linux + Windows + macOS" milestone. At the same time, delete
  the macOS `packageVersion = "1.0.0"` override in
  `clients/desktop/build.gradle.kts` so macOS uses the shared version.
  (Linux was at 0.1.3, so jumping to 1.0.0 is fine.) If Windows/macOS
  aren't ready yet, it's also fine to cut `desktop-v1.0.0` with Linux
  only and add them in a later desktop release.
- **Mobile:** rename `ratatoskrAndroidVersion` → `ratatoskrMobileVersion`
  and update its readers (`clients/android/build.gradle.kts`; later the
  iOS build). Android's `versionCode` (MAJOR×10000 + MINOR×100 + PATCH)
  must keep increasing past the last Android release (0.1.2 → 102), so
  just continue the numbering: e.g. the biometrics release as
  `mobile-v0.2.0`. When the mobile side goes to 1.0.0 is the owner's call
  (a natural point is when iOS joins). iOS will need the same version as
  `CFBundleShortVersionString`, plus an always-increasing build number
  (`CFBundleVersion`); derive it the same way as `versionCode`.
- The Android signing key and `applicationId` don't change, so APKs from
  `mobile-v` releases install as updates over the existing app.
- Desktop and mobile versions are independent. A desktop-only fix
  doesn't touch mobile, and vice versa.

**Workflows** (build each by evolving the existing one; delete the old
file once the new one has shipped a release):

- **`desktop-release.yml`**, from `linux-client-release.yml`:
  - Triggers: `push: tags: ["desktop-v*"]` and `workflow_dispatch`
    (artifacts only, no release; this is how test builds reach the
    Windows VM and the Mac before tagging).
  - A `version` job checks the tag matches `ratatoskrDesktopVersion`.
  - Jobs `linux`, `windows`, `macos` in parallel, each uploading its files
    with `actions/upload-artifact`. Run `:shared:jvmTest` once, in
    `linux`.
  - A `release` job (`needs:` all three, tag pushes only) downloads the
    artifacts, writes one `SHA256SUMS`, and runs `gh release create` with
    notes giving install steps per OS: dnf/apt/AppImage; Windows
    SmartScreen's "More info → Run anyway"; macOS's Privacy & Security
    "Open Anyway" (Phase 5).
  - Start with just `linux` + `release`, and add `windows`/`macos` as
    Phases 4/5 land.
- **`mobile-release.yml`**, from `android-client-release.yml`:
  - Triggers `mobile-v*` + `workflow_dispatch`, with the version check
    against `ratatoskrMobileVersion`.
  - Keep everything the Android workflow already does: the keystore check
    that names the wrong secret, job-wide signing variables, Gradle-error
    annotations, the `apksigner` fingerprint (with `pipefail`), and
    install steps + fingerprint in the notes.
  - Restructure it as an `android` job + a `release` job, so an `ios` job
    can slot in later (Phase 6).
- Carry over from the existing workflows: pinned, checksum-verified tool
  downloads (nfpm, appimagetool); `set -o pipefail` around anything piped
  through `tee`; and failures surfacing as annotations.

**After switching:**
- root `README.md`: the Platforms table, Getting started and "Versions and
  releases";
- `clients/README.md`: "Versioning and releases" and the Android
  "Releases" section;
- this file's status table and "How work gets shipped".

Old `linux-v*`/`android-v*` releases stay on GitHub for history.

### Desktop session storage (all three desktop OSes)

`DesktopSessionStore` (in `clients/desktop`) already has the per-OS
config paths and stores the server address. To also keep people signed in
across launches, the token must go into the OS's secret store (the
Android equivalent, `KeystoreSessionStore`, is the model), and **must not
be written to disk in plain text**:

- **Windows:** DPAPI. JNA's `jna-platform` has `Crypt32Util.cryptProtectData`
  / `cryptUnprotectData`; encrypt the token for the current user and keep
  the ciphertext next to `settings.properties` in `%APPDATA%\Ratatoskr`.
  Small, and JNA is well maintained.
- **macOS:** the login Keychain, simplest via the built-in
  `/usr/bin/security add-generic-password` / `find-generic-password`
  (or JNA to the Security framework).
- **Linux:** libsecret over D-Bus (the Secret Service API that KDE Wallet
  and GNOME Keyring both provide), e.g. via `secret-tool` or a D-Bus
  library. Handle "no keyring available" by falling back to today's
  behaviour (address only).
- The all-in-one `java-keyring` library covers all three, but its last
  release was 1.0.4 in 2023; check its state before depending on it.

The shared code needs no change: `AppState` already restores a token
when the `SessionStore` returns one.

### Phase 4: Windows

Mostly packaging on the desktop app that already exists: `clients/desktop`
runs on Windows unchanged (Compose Desktop on the JVM), and its
`build.gradle.kts` already lists `TargetFormat.Exe`/`Msi` with a
`windows {}` block. **Deliverable: `Ratatoskr-X.Y.Z.exe`**, a jpackage
installer, built by the `windows` job of `desktop-release.yml`.

**Must be settled before the first Windows release (permanent after):**

- **`upgradeUuid`** in `windows {}`: a fixed, randomly generated UUID
  (jpackage's `--win-upgrade-uuid`, used by both `.exe` and `.msi`).
  Without it each version installs *alongside* the previous one instead
  of upgrading it. Generate once (`uuidgen`), commit it, never change it.
- Keep `perUserInstall = true` (no admin rights needed), and set
  `shortcut = true` and `menu = true` so it gets a Start menu entry (under
  `menuGroup`, currently "Ratatoskr") and a desktop shortcut.

**To do:**

- **Icon:** replace the placeholder PNG with a real `.ico` holding 16–256
  px sizes, e.g. `magick clients/desktop/icons/ratatoskr_512.png -define
  icon:auto-resize=256,128,64,48,32,16 clients/desktop/icons/ratatoskr.ico`
  (commit the `.ico`; ImageMagick is on the dev machine).
- **Installer toolchain:** jpackage's `.exe`/`.msi` need the WiX Toolset.
  JDK 21's jpackage expects WiX **3.x** (WiX 4+ support only arrived in
  later JDKs). Check what the `windows-latest` image provides; if needed,
  install WiX 3 in the job (e.g. `choco install wixtoolset`, pinned).
- **Version:** Windows installer versions are `MAJOR.MINOR.BUILD` with
  range limits per part; 1.0.0-style versions are fine, and desktop
  releases start at 1.0.0 (see Release plan).
- **The `windows` job:** `runs-on: windows-latest`, Temurin 21 (includes
  jmods, needed by jlink), `./gradlew --no-daemon :desktop:packageExe`,
  upload `desktop/build/compose/binaries/main/exe/*.exe` renamed to
  `Ratatoskr-X.Y.Z.exe`. Use `shell: bash` (Git Bash is on the runner) so
  the scripts match the Linux/Android jobs. `./gradlew` works in bash;
  `gradlew.bat` is the cmd equivalent.
- **Runtime modules:** jlink's module list (`modules(...)` in
  `nativeDistributions`) was set from `suggestRuntimeModules` on Linux;
  re-check it on Windows in case the Windows AWT/TLS stack needs more
  (e.g. `jdk.crypto.mscapi` for the Windows certificate store, which
  matters for `https://` servers).
- **Session storage:** `DesktopSessionStore` already uses
  `%APPDATA%\Ratatoskr`; "stay signed in" via DPAPI is under Desktop
  session storage above.
- **Windows Hello** (the Windows equivalent of Touch ID) hasn't been
  requested; it's reachable only through WinRT APIs, awkward from the JVM,
  so leave it unless asked.

**Testing in the Windows VM** (see machine setup above): install from the
`workflow_dispatch` artifact, then check:
- it installs without admin rights;
- the Start menu entry and desktop shortcut appear with the right icon;
- the taskbar icon while running;
- connect, unlock and entries against a server reachable from the VM (the
  host's LAN address, or Tailscale inside the VM);
- the server is remembered after restarting the app;
- installing the next build upgrades in place (the `upgradeUuid` test);
- uninstalling is clean.

**SmartScreen:** unsigned installers show "Windows protected your PC";
users click *More info → Run anyway*, and the release notes must say so.
Removing that needs a code-signing certificate (paid, or a hosted signing
service), a cost decision for later.

### Phase 5: macOS (Apple silicon only)

Same pattern: packaging on the existing desktop app, with a `macOS {}`
block already in `clients/desktop/build.gradle.kts`. **Deliverable:
`Ratatoskr-X.Y.Z-arm64.pkg`** (`TargetFormat.Pkg`, installs into
/Applications), built by the `macos` job on `macos-latest`, which is
Apple silicon. Intel Macs are out of scope (owner's decision), so there's
no x86_64 build or universal binary; say "Apple silicon" in the release
notes.

**Must be settled before the first macOS release (permanent after):**

- **`bundleID`** is still the placeholder `com.ratatoskr.desktop`. Change
  it to the project's app ID, `io.github.curtis04ben.Ratatoskr`, *before*
  the first release: macOS keys preferences, Keychain items and privacy
  permissions on it.
- **Bundle version:** macOS needs the version's major part > 0. The
  desktop releases starting at **1.0.0** solve this; delete the
  hard-coded `packageVersion = "1.0.0"` in the `macOS {}` block at the
  same time so the desktop version flows through.
- **Signing:** unsigned vs Developer ID signed + notarized (below). It
  decides how Gatekeeper treats downloads, and whether Touch ID can be
  done properly.

**To do:**

- **Icon:** a real `.icns`. Generate on the macOS runner (or the Mac):
  make a `ratatoskr.iconset/` of PNGs (`icon_16x16.png` … `icon_512x512@2x.png`,
  16–1024 px) from `shared/assets/ratatoskr-self-hosted.jpg` (1024 px, so
  no upscaling), then `iconutil -c icns ratatoskr.iconset`. Commit the `.icns`.
- **The `macos` job:** `runs-on: macos-latest`, Temurin 21 for arm64,
  `./gradlew --no-daemon :desktop:packagePkg`, rename the output to
  `Ratatoskr-X.Y.Z-arm64.pkg`.
- **Code signatures on Apple silicon:** arm64 code must carry at least an
  ad-hoc signature to run at all. Confirm the built app has one
  (`codesign -dv --verbose=2 /Applications/Ratatoskr.app`) before
  worrying about anything else.
- **Mac conventions to check:** the app menu shows "Ratatoskr" with
  About/Quit (⌘Q), standard ⌘ shortcuts (⌘C/⌘V in text fields), the Dock
  icon, window restore, and that `~/Library/Application Support/Ratatoskr/settings.properties`
  remembers the server.
- **Runtime modules:** re-check the jlink module list on macOS, as for
  Windows.

**Gatekeeper (unsigned builds).** Downloaded, unsigned, un-notarized
apps and installers are blocked on first open. On current macOS (15 and
later), right-click → Open no longer overrides this. Users must try to
open it once, then go to **System Settings → Privacy & Security → "Open
Anyway"** (for the `.pkg`, and possibly again for the app). The release
notes need these steps. The alternative is an Apple Developer account
($99/year) for Developer ID signing + notarization. Compose supports it:
`macOS { signing { sign.set(true); identity.set(...) }; notarization { ... } }`,
with the certificate (`.p12`, base64) and an App Store Connect API key
as CI secrets, the same pattern as the Android keystore. Decide at the
start of this phase; it's also the deciding factor for Touch ID below.

**Touch ID unlock** (after Android's Phase 3.1, same `BiometricUnlock`
interface):

- The proper way: store the master password (same design and caveats as
  Phase 3.1) in a **Keychain item with an access control of
  `.biometryCurrentSet`**. macOS itself then demands Touch ID to read it,
  and invalidates it when fingerprints change. That kind of item lives in
  the *data protection* keychain, which requires the app to be **signed
  with a keychain-access-groups entitlement**, so it needs the Developer
  ID signing above.
- From the JVM, the practical route is a tiny **Swift helper executable**
  bundled inside the app (store / read / delete, using the
  `Security` and `LocalAuthentication` frameworks), called by the desktop
  `BiometricUnlock` implementation over stdin/stdout. Build it on the
  macOS runner with `swiftc` and add it to the bundle (Compose
  `appResourcesRootDir` or jpackage `--app-content`), signed along with the
  app. JNA straight into the Objective-C runtime is possible but much
  fiddlier.
- **If the app stays unsigned:** the only option is a
  `LAContext.evaluatePolicy` yes/no check in front of an ordinary
  Keychain item. That is a UI gate, not encryption bound to the
  fingerprint. Don't present it as equivalent. Either skip Touch ID for
  unsigned builds or label it clearly, and make this call together with
  the signing decision.

### Phase 6: iOS

The newest, least battle-tested part of Compose Multiplatform, so expect
more friction here than the other phases. Work: an `iosMain` for
`:shared` (targets `iosArm64` + `iosSimulatorArm64`; the Ktor engine for
iOS is `ktor-client-darwin`), an Xcode app project that hosts the Compose
UI, plus iOS versions of `SessionStore` (Keychain), `PlatformFiles`
(`UIDocumentPickerViewController`) and `BiometricUnlock`.

**Face ID / Touch ID** on iOS is the easy case: Kotlin/Native can call
`platform.LocalAuthentication` and `platform.Security` directly from
`iosMain`. Store the secret in a Keychain item with
`SecAccessControlCreateWithFlags(..., kSecAccessControlBiometryCurrentSet)`.
**`NSFaceIDUsageDescription` must be in Info.plist**, or the app crashes
the first time Face ID is used.

Distribution is materially different from everywhere else: there's no
"download and run". Realistically it means Xcode builds onto the owner's
own devices, or TestFlight, both needing an Apple Developer account
(shared with the macOS signing decision). iOS won't be in the Mobile
GitHub Release as a file; the release notes can point to TestFlight if
that's used. Set expectations accordingly: "v1 for iOS" isn't the same
yardstick as the other platforms.

## Testing note for whoever picks this up next

The client code was first written without a Kotlin compiler available and
checked by static analysis only, including verifying `Totp.kt` against the
RFC 6238 test vectors via a Python transliteration. It has since been
built for real and is exercised by CI on every release:

- `./gradlew :shared:jvmTest` runs the unit tests, including the TOTP test
  vectors; both release workflows run it before building anything.
- The Linux packages were installed and run on Fedora KDE (launcher entry,
  icon, window matching).
- The Android app was tested on a real phone (Galaxy A55, Android 16)
  against a scratch server: setup, creating an entry, repeated
  kill-and-relaunch session restores, and Lock. The Android emulator
  crashed on the development machine's kernel at the time, so real-device
  testing over `adb` is the proven route; see `clients/README.md` for the
  local-server setup.

Not yet exercised on a device: the entry editor's live 2FA code, and CSV
import/export through Android's file picker.
