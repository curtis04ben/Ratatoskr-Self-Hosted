# Ratatoskr (Self-Hosted)

A free, self-hosted, multi-user password manager for a household or small trusted
group. One small server holds everyone's vault; you reach it from a web
browser, a Linux desktop app or an Android app, with more platforms on the
way.

Named for the squirrel who runs up and down Yggdrasil carrying messages
between the eagle at its crown and the serpent at its roots, a fitting
namesake for something that carries your credentials exactly where they
need to go, and nowhere else.

Don't want to run a server? **Ratatoskr** (without "Self-Hosted") is a
separate, standalone Android app that keeps an encrypted vault on the
phone itself, with no account and no internet access. It's a different
project, headed for the Google Play Store; this one stays free.

## Platforms

| Platform | Status | Get it |
|---|---|---|
| **Web** | ✅ Available | Built into the server. Open it in any browser |
| **Linux** desktop | ✅ Available | [Releases](https://github.com/curtis04ben/Ratatoskr-Self-Hosted/releases), "Ratatoskr Linux client" (`.rpm`, `.deb`, AppImage) |
| **Android** | ✅ Available | [Releases](https://github.com/curtis04ben/Ratatoskr-Self-Hosted/releases), "Ratatoskr Android" (`.apk`, Android 8.0+) |
| **Windows** | 🚧 In the works | Same desktop app as Linux; needs Windows packaging |
| **macOS** | 🚧 In the works | Same desktop app as Linux; needs macOS packaging |
| **iOS** | 🗓️ Planned | |
| **Browser extension** | 🗓️ Planned | Separate project, using the same server API |

All the apps are clients of a Ratatoskr server you host yourself. There's no
hosted service and no account with anyone else. See
[Getting started](#getting-started).

## What it does

- **Multiple people, one server.** Each person has their own account and
  master password. Entries are private to their owner unless shared.
- **Three roles.** *Admins* can see and manage everything (for maintenance
  and recovery), *Users* have their own vault, and *Visitors* can only read
  what's shared with them.
- **Per-entry sharing.** Share a single login with someone, read-only or
  editable, and revoke it later.
- **Invite-only sign-up.** An admin creates a one-time invite code; the
  invitee picks their own master password, which the admin never sees.
- **Change your master password** from any of the apps or the web UI.
  Your entries and shares are untouched, and every other device you're
  signed in on is signed out.
- **Two-factor codes.** Any entry can hold a TOTP secret and shows the live
  6-digit code with a countdown, so the vault doubles as your authenticator.
- **Password generator**, and **CSV import/export** that understands
  exports from Chrome, Bitwarden and Firefox as well as its own format.
- **Native apps that feel native.** The Linux app installs into your
  application launcher like any other program. The Android app stays
  signed in when Android closes it in the background (until the server's
  idle timeout), keeping the session encrypted with a key in the Android
  Keystore, hardware-backed on phones that support it, and blocks
  screenshots of your vault.

## How it works

```
   Browser (web UI)      Linux app       Android app
          │                  │                │
          └──────────────────┼────────────────┘
                             │  REST API (/api/v1), over your own network
                  ┌──────────┴──────────┐
                  │   Ratatoskr server  │   FastAPI + one SQLite file,
                  │      (Docker)       │   on your NAS or home server
                  └─────────────────────┘
```

**The server** is the core: a FastAPI app in a single Docker container,
storing everything in one SQLite file. It also serves the web UI. It runs
comfortably on a NAS (TrueNAS, CasaOS) or any Docker host.

**Encryption.** Every account has its own X25519 keypair. The private half
is encrypted with a key derived from that person's master password using
Argon2id, and the master password itself is never stored anywhere. Every
entry has its own random key, and a copy of that key is sealed to each
account allowed to open it, which is how sharing and revoking work without
re-encrypting anything. The full design, including how admin access works
and its one real limitation, is in
[`server/README.md`](server/README.md#how-multi-user-security-works).

**Worth knowing:** decryption happens on the server when you unlock, and
the apps receive already-decrypted entries over the connection. That's why
the server should stay on a private network, ideally reached over
[Tailscale](https://tailscale.com) or similar, rather than exposed to the
internet. Moving to true end-to-end encryption, where the server never sees
plaintext, would be a deliberate redesign and isn't planned right now.

**The apps** are written once in Kotlin with Compose Multiplatform: the
screens, API client and 2FA code generator are shared, and each platform
adds only what's genuinely platform-specific (window and launcher
integration on desktop; Keystore session storage and the system file
picker on Android). That shared code is why Windows and macOS are mostly
packaging work on the existing desktop app rather than new apps.

## Getting started

1. **Run the server.** On any machine with Docker:
   ```bash
   git clone https://github.com/curtis04ben/Ratatoskr-Self-Hosted.git
   cd Ratatoskr-Self-Hosted/server
   docker compose up -d --build
   ```
   Then open `http://<host>:8000` and create the admin account. See
   [`server/README.md`](server/README.md) for TrueNAS, CasaOS, where your
   data lives, backups and configuration.
2. **Invite anyone else** from the web UI's **Users** panel.
3. **Install the apps** you want from the
   [Releases page](https://github.com/curtis04ben/Ratatoskr-Self-Hosted/releases), and
   enter your server's address the first time you open them:
   - **Fedora / RHEL:** `sudo dnf install ./ratatoskr-<version>-1.x86_64.rpm`
   - **Debian / Ubuntu:** `sudo apt install ./ratatoskr_<version>_amd64.deb`
   - **Any Linux, no install:** make `Ratatoskr-x86_64.AppImage` executable and run it
   - **Android:** download `Ratatoskr-<version>.apk` on your phone and open
     it, allowing installs from your browser when asked

   The Linux packages bundle their own Java runtime, so there's nothing else
   to install. Every release lists SHA-256 checksums, and Android releases
   also give the signing certificate's fingerprint.

### What the apps don't do yet

The Linux and Android apps cover everyday use: unlocking, searching,
viewing, adding, editing and deleting entries, 2FA codes, the password
generator, CSV import/export and changing your master password. They
remember your server after the first time, so later launches only ask for
your username and master password (**Change server** on that screen
switches to another one). The Android app can also be your phone's
autofill service, suggesting saved logins in other apps and browsers and
offering to save new ones. A few things still need the web UI:

- sharing entries with other people;
- the admin **Users** panel (creating invites, changing roles, removing
  accounts);
- the "no admin can log in" factory reset.

## Repository structure

```
server/               FastAPI backend + web UI: the self-hosted core.
                      Deployment, security model and API: server/README.md
clients/              Native apps (Kotlin Multiplatform + Compose Multiplatform).
                      Building, packaging and releasing: clients/README.md
  clients/shared/       API client, models and every screen, shared by all apps
  clients/desktop/      Desktop app: Linux today, Windows/macOS next
  clients/android/      Android app
shared/assets/        Master logo (ratatoskr-self-hosted.jpg) that every icon is made from
docs/                 Architecture and per-platform development plan
.github/workflows/    Release builds for the Linux and Android apps
```

## Versions and releases

Each part of Ratatoskr is versioned on its own, so updating one never
forces a download of another:

- **Server**: currently 2.4.0 (see the version history in
  [`server/README.md`](server/README.md#version-history)); deployed from
  source with Docker.
- **Linux app**: tags `linux-vX.Y.Z`, each a separate GitHub Release.
- **Android app**: tags `android-vX.Y.Z`, each a separate GitHub Release.

## Development

- [`docs/development-plan.md`](docs/development-plan.md): why the project
  is shaped this way, and the plan for each platform. Start here if you're
  picking up client work.
- [`clients/README.md`](clients/README.md): building, testing, packaging
  and releasing the apps.
- [`server/README.md`](server/README.md): running the server locally, the
  REST API, and configuration.
