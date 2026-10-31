# Ratatoskr server

The server for Ratatoskr, a self-hosted, multi-user password manager.
FastAPI backend, zero external JS dependencies on the web UI, single SQLite
file for storage, built to run as one Docker container on TrueNAS, CasaOS,
or plain Docker.

Named for the squirrel who runs up and down Yggdrasil carrying messages
between the eagle at its crown and the serpent at its roots, a fitting
namesake for something that moves your credentials exactly where they need
to go, and nowhere else.

> This is the server component of the larger Ratatoskr project. It stores
> the vault, does the cryptography, and serves the **web UI**. The native
> **Linux** and **Android** apps (in [`../clients/`](../clients/),
> downloadable from the
> [Releases page](https://github.com/curtis04ben/Ratatoskr-Self-Hosted/releases)) are
> clients of this server's REST API; Windows and macOS are in the works,
> and iOS and a browser extension are planned. See the
> [project README](../README.md) for the overview and
> [`../docs/development-plan.md`](../docs/development-plan.md) for the
> multi-client architecture.

## How multi-user security works

Every account (Admin, User, or Visitor) gets its own **X25519 identity
keypair**, generated when the account is created:

- The **public** half is stored in plain in the database. Public keys
  aren't secret, and anyone can encrypt *to* a person using theirs.
- The **private** half is encrypted with a key derived from that person's
  own master password (via Argon2id, slow and memory-hard) and stored only
  in that wrapped form. The master password itself is never stored,
  anywhere, for anyone.

Each saved entry has its own random encryption key. A sealed copy of that
key is stored for every account that can open the entry. That's the owner
always, plus anyone it's explicitly shared with. "Sealing" is asymmetric
(an ECIES-style construction built from X25519 + HKDF + AES-GCM): whoever
shares an entry only needs the *recipient's public key* to do it, so
sharing works even if the recipient isn't online, and revoking access is
just deleting their sealed copy. The entry's own ciphertext is never
touched.

**Changing your master password doesn't touch anyone else's data.** It
only re-wraps your own private key under a new Argon2id key; your
underlying X25519 keypair is unchanged, so every seal anyone has ever made
to your public key, whether for your own entries, things shared with you or
admin recovery access, stays valid without re-sharing anything.

To change it, use **Change password** in the web UI's header (or
**⋮ → Change master password** in the Linux and Android apps). It asks for
your current password, and on success signs you out everywhere else: every
other session for your account is ended, and only the device you changed
it on stays signed in.

### Roles

| Role | Can see | Can write | Notes |
|---|---|---|---|
| **Admin** | Every entry, from every user | Yes, everywhere | For maintenance/troubleshooting. See "Admin oversight" below for how this works without breaking the encryption model. |
| **User** | Their own entries, plus anything explicitly shared with them | Their own, and shared entries marked editable | Default role. Entries are private to each user unless shared. |
| **Visitor** | Only entries explicitly shared with them | Never | Hard-enforced server-side, regardless of what a sharer's toggle says. |

Sharing is per-entry and explicit: open an entry you own (or, as an admin,
any entry) and use **Share** to grant another username read or read/write
access.

### Admin oversight, and its one real limitation

For "Admin can access everything" to be true without secretly weakening
everyone else's encryption, Ratatoskr uses one extra piece: a **tree-wide
admin-recovery keypair**, generated once when the first admin account is
created. Every entry, at creation time, also gets a copy of its key sealed
to this recovery keypair's public half. Any current admin can unseal the
recovery *private* key using their own identity key (they're granted a
sealed copy of it too), and from there open any entry in the system.

When an existing admin promotes someone new to Admin, that promotion step
re-seals the recovery private key to the new admin's public key, so
promotion, not account creation order, is what grants full visibility.

**The honest limitation:** if every admin account is ever deleted, the
recovery private key is gone for good. There's no cryptographic back
door, by design. The only path forward at that point is the factory-reset
flow described below, which starts over from nothing.

### If nobody can log in

There's no password-reset email (there's no email). If an individual user
forgets their master password, there's currently no recovery path for
*their* data specifically. An admin can still see anything that was
shared with them or created after that admin's promotion, but anything
that existed only in that one person's private vault is gone. If **no
admin account** can be logged into at all, the lock screen has a "no admin
can log in" option that performs a full factory reset: every account and
every entry, deleted, so you can `/setup` a fresh admin and start over.
This is deliberate. The alternative would mean a back door into
everyone's data, which defeats the point.

## Running it

### Quick start (any Docker host)

From the repo's `server/` folder:

```bash
cd server
docker compose up -d --build
```

Then open `http://<host>:8000`. First load walks you through creating the
admin account. No manual `mkdir`/`chmod` needed. See "Persistent data"
below for why.

### Upgrading an install from before the `server/` move

Older checkouts had the server at the repo root, with its vault in a
root-level `data/` folder. After pulling the new layout, move that folder
into `server/` **before** starting the container again. Otherwise the
compose file mounts an empty `server/data`, and you'll get a fresh,
un-set-up vault instead of yours (the old one is untouched, just not
mounted):

```bash
docker compose down        # run this one before `git pull`, from the repo root
git pull
mv data server/data        # skip if your volume points at an absolute path
cd server && docker compose up -d --build
```

### Deploying on TrueNAS Community Edition (SCALE)

1. Create a dataset for persistent data, e.g. `/mnt/<pool>/apps/ratatoskr`.
2. SSH into TrueNAS, `cd` to wherever you keep app source (e.g.
   `/mnt/<pool>/apps`), and `git clone` this repo (or copy the extracted
   zip over).
3. Edit `server/docker-compose.yml`'s volume line to point at your dataset, e.g.
   `- /mnt/<pool>/apps/ratatoskr-data:/data`.
4. `cd server && docker compose up -d --build`.
5. Visit `http://<truenas-ip>:8000`.

Alternatively, TrueNAS's Apps UI **Custom App** screen accepts a
pre-built image (rather than building from a Dockerfile on the box) if you
push one to a registry like GHCR first, which is useful once you're iterating less
and want TrueNAS to manage restarts/updates through its own UI instead.

### Deploying on CasaOS

CasaOS is Docker underneath, so the same approach works:

1. SSH into the box, `cd` into wherever you keep app data (e.g.
   `/DATA/AppData`), and clone the repo.
2. Adjust the compose file's volume path if you want the data folder named
   or placed differently.
3. `cd server && docker compose up -d --build`.
4. If CasaOS's dashboard "Open" button doesn't appear or has no link, its
   generated compose config may not have a `port_map` set. Either browse
   directly to `http://<host>:8000`, or edit the app's settings in the
   CasaOS UI and set its web UI port to `8000`.

### Persistent data

The vault database (`ratatoskr.db`) lives in whatever host directory you
bind-mount to `/data`, whether that's a plain `./data` folder, a TrueNAS dataset, a
CasaOS AppData path, wherever you pointed it. On container start, an
entrypoint script (`docker-entrypoint.sh`) briefly runs as root just long
enough to fix that directory's ownership and permissions (owner-only,
`700`) for the app's own unprivileged user, then drops to that user for
the entire running process. The application itself never runs as root.
This means a fresh deployment works with a plain `docker compose up`, no
manual `mkdir`/`chmod` step, regardless of whether Docker auto-creates the
folder as root or you created it yourself first.

Because it's a plain host directory rather than a Docker-managed named
volume, backing it up is just copying files:

```bash
# stop first so the copy is consistent, rather than mid-write
docker compose stop
cp -r ./data ./data-backup-$(date +%Y%m%d)
docker compose start
```

The database survives container restarts, `docker compose up -d --build`
rebuilds, and image recreation. None of those touch the host directory
itself, only the container that mounts it. It's also why the host
bind-mount approach was kept over a Docker-managed named volume: a named
volume would have solved the permissions bug too, but you'd lose the
ability to point it at a specific TrueNAS dataset or CasaOS AppData folder
for snapshots/replication/whatever your NAS already handles for you.

### Keep it off the open internet

There's no rate limiting beyond Argon2's inherent cost, and while the
multi-user model is real, this is still built for a small trusted group
(a household, a few housemates), not the public internet. Put it behind
Tailscale or another private network rather than a public reverse-proxy or
port-forward.

### Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `RATATOSKR_DATA_DIR` | `/data` | Where the SQLite file lives |
| `RATATOSKR_SESSION_TIMEOUT` | `1800` | Idle session length, seconds |
| `RATATOSKR_INVITE_EXPIRY` | `604800` (7 days) | How long an admin-issued invite code stays redeemable |
| `RATATOSKR_EXTRA_ORIGINS` | *(empty)* | Extra CORS origins, comma-separated, for a future browser extension |
| `RATATOSKR_ARGON2_TIME_COST` | `3` | Argon2id iterations |
| `RATATOSKR_ARGON2_MEMORY_KIB` | `262144` (256 MiB) | Argon2id memory cost. Lower only if unlock is too slow on the host |
| `RATATOSKR_ARGON2_PARALLELISM` | `2` | Argon2id lanes |

## CSV export / import

The toolbar's **Export** button downloads every entry you can currently
see (your own, anything shared with you, or, as an admin, everything) as
a plain CSV file. **Import** adds entries from a CSV file, recognizing
common header names from other password managers, such as Chrome/Bitwarden-style
`name`/`login_username`/`login_password`/`login_uri`, Firefox's own export
(`url`/`username`/`password`, with no site/name column at all, so the site
name gets derived from each row's URL automatically), as well as
Ratatoskr's own `site`/`username`/`password`/`url`/`notes`, so you can
move data in from elsewhere without reformatting it by hand first. A CSV
whose columns don't match any recognized format gets a clear error saying
so, rather than being misreported as a missing header row.

**Read this before using either one:** a CSV file is plain text. Exporting
puts every visible password, and every visible 2FA secret if any entries
have one, on disk, unencrypted, on whatever device does the download.
There's no way to make that safe in the format itself, only in how
carefully you handle the file afterward (delete it once you're done, don't
let it sync to a cloud backup folder, etc.). Import has the mirror
problem. Whatever CSV you feed it is assumed to already be sitting
somewhere on your machine, so treat the source file with the same care.
Visitors can't import (read-only), but can export what's been shared with
them, since export doesn't grant them anything they couldn't already see.

## Two-factor (TOTP) codes

Any entry can optionally hold a 2FA secret, since not every login needs
one. Your NAS might not, but your Google account probably does. Toggle "This
login uses 2FA" in the entry editor and paste in the secret key from the
site's 2FA setup screen (the "can't scan the QR code? enter this key
manually" fallback every 2FA setup flow offers) rather than a live 6-digit
code. Once saved, opening that entry shows a live, auto-refreshing code
with a countdown ring, generated with the standard algorithm (RFC 6238,
the same one Google Authenticator and every other TOTP app uses), so
codes match exactly what the site itself expects.

**How the code gets computed matters for where this can run:** the web UI
computes it entirely client-side, in plain JavaScript, deliberately without
using the browser's built-in `crypto.subtle`. That API only works in
"secure contexts" (HTTPS or `localhost`), and this app is frequently
reached over plain HTTP via Tailscale. Under those conditions
`crypto.subtle` is simply unavailable, so relying on it would silently
break 2FA display for exactly the kind of homelab deployment this project
targets. The hand-written implementation (`static/totp.js`) is checked
against the official RFC 6238 test vectors and cross-verified against an
independent Python implementation for correctness.

The native apps do the same: they share one pure-Kotlin implementation
(`clients/shared/.../totp/Totp.kt`), checked against the same RFC 6238
test vectors, so codes tick over every second offline there too.

There's also a server-side endpoint, `GET /api/v1/vault/{id}/totp`, that
computes the same code using the identical algorithm in Python
(`app/crypto.py`). None of the current interfaces use it, since computing
locally means no network round trip per code, but it's there for thin
clients, such as a future browser extension, that would rather not
implement TOTP themselves.

Sharing an entry that has 2FA configured shares the code along with the
password, using the same per-recipient sealing as everything else. A
Visitor granted read access to a shared login sees its rotating code too,
not just the password.

## Inviting other people

There's no self-service sign-up. An Admin creates an invite from the
**Users** panel (top right, admin-only), choosing a username and role
(Admin / User / Visitor). This returns a one-time invite code, shown once,
that the admin passes to the invitee out of band (in person, chat,
whatever channel you'd trust with a temporary code). The invitee enters
that code plus their own chosen master password at the lock screen's
**"Have an invite code?"** link. The admin never sees or sets their
password.

## API

Everything the web UI and the native apps do goes through a plain REST API
at `/api/v1`:

- `GET  /api/v1/auth/status`: is a vault set up yet
- `POST /api/v1/auth/setup`: first run, create the admin account
- `POST /api/v1/auth/unlock`: username + master password → bearer token
- `POST /api/v1/auth/accept-invite`: redeem an invite code
- `GET  /api/v1/auth/me`: current username/role
- `POST /api/v1/auth/lock`
- `POST /api/v1/auth/change-password`
- `POST /api/v1/auth/factory-reset`: last-resort wipe (see above)
- `GET/POST/PUT/DELETE /api/v1/vault[/{id}]`: entries
- `GET  /api/v1/vault/{id}/totp`: current 2FA code + seconds remaining, for thin clients
- `GET/POST/DELETE /api/v1/vault/{id}/shares[/{username}]`: sharing
- `GET  /api/v1/vault/export` / `POST /api/v1/vault/import`: CSV
- `POST /api/v1/generate`: password generator
- `GET  /api/v1/users`: list accounts (admin only)
- `POST /api/v1/users/invite`: create an invite (admin only)
- `PATCH /api/v1/users/{id}/role`: change a role (admin only)
- `DELETE /api/v1/users/{id}`: remove an account (admin only)

Interactive docs are auto-generated at `/docs` (Swagger UI), useful for
prototyping a browser extension or native client against the API directly.

For a browser extension specifically: set `RATATOSKR_EXTRA_ORIGINS` to its
`chrome-extension://<id>` / `moz-extension://<id>` origin once you have
one, since extensions call the API from their own origin rather than the
page you're viewing.

## Local development (without Docker)

```bash
cd server
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
RATATOSKR_DATA_DIR=./data uvicorn app.main:app --reload
```

## Known limitations / roadmap

- **Clients:** web UI, plus native Linux and Android apps. Windows and
  macOS are in the works; iOS and a browser extension are planned. The
  native apps don't yet have sharing, the admin **Users** panel or the
  factory reset. Use the web UI for those.
- No individual "forgot my password" recovery. See "If nobody can log
  in" above for the current trade-off and why.
- No attachments, no folders/tags. Entries are site, username, password,
  2FA secret, URL, notes. The `EntryIn`/`EntryOut` schema and the
  encrypted JSON blob are straightforward to extend when you want more
  fields.
- No password-strength meter or breach checking (the latter means calling
  an external API, which changes the privacy story, so it's worth deciding
  deliberately rather than adding by default).
- CSV import matches common header names but hasn't been tested against
  every real export format out there (1Password's in particular uses a
  1PUX/JSON export by default, not CSV, so you'd need to convert first).
- Deleting a user cascades to entries only they could decrypt. There's no
  "transfer ownership before deleting" flow yet. Share important entries
  with another account (or an admin) before removing someone.

## Version history

- **2.4.0**: **Change password** in the web UI's header: current password
  plus a confirmed new one, using the existing change-password API, which
  signs out every other session for the account. A wrong current password
  now shows an error in the dialog instead of being treated as an expired
  session. The header's buttons wrap on narrow (phone-width) screens
  rather than running off the edge.
- **2.3.0**: Firefox CSV export support (site name derived from URL,
  since Firefox's export has no site column), clearer distinction between
  malformed/unsupported/invalid-row import errors. Docker deployments no
  longer need a manual `mkdir`/`chmod` step. An entrypoint script now
  fixes the bind-mounted data directory's ownership automatically,
  restrictively (owner-only), on container start. New logo and a
  substantially more detailed Yggdrasil background illustration.
- **2.2.0**: Optional per-entry TOTP (2FA) codes, toggleable per login
  since not every site needs one. Web UI computes codes client-side
  without relying on the browser's `crypto.subtle` (unavailable over
  plain HTTP, which is how this is usually deployed); a server-side
  endpoint offers the same computation for thin clients. 2FA secrets flow
  through sharing and CSV export/import the same way passwords do.
- **2.1.0**: CSV export/import, with an explicit in-app warning and
  acknowledgement step before exporting, since a CSV is unencrypted the
  moment it hits disk.
- **2.0.0**: Full rebuild from the original single-user PassVault:
  multi-user accounts, three roles (Admin/User/Visitor), per-entry
  sharing via asymmetric key sealing, invite-based account creation, and
  the Ratatoskr rebrand/artwork.
- **1.0.0**: Original single-user PassVault: one vault, one master
  password, password generator, self-hosted via Docker.
