# Linux auto-updater (pkexec) — Design

**Date:** 2026-06-16
**Status:** Approved, pending implementation plan
**Scope:** Bring the Linux `.deb` build to feature parity with the Windows in-app updater, so users update from inside the app instead of manually reinstalling a newer `.deb`.

## Problem

The in-app updater (`/api/version/check|download|install`) is Windows-only. On Linux, `api_version_check` returns `auto_install: false` and the Updates panel only surfaces a manual link to the `.deb`. The core obstacle is that installing a `.deb` needs root, while the app runs as a normal user (system Python, installed read-only under `/opt/clarifi`).

## Solution overview

Keep the existing `.deb` packaging. Add a Linux branch to the three updater endpoints that downloads the release `.deb` and installs it with `pkexec apt-get install -y <deb>`. `pkexec` (polkit) provides a native graphical password dialog: that is the answer to the sudo problem, no terminal involved. Replacing files under `/opt/clarifi` while the process runs is safe on Linux (no file-locking like Windows), so the flow can be cleaner than Windows. After a successful install the app auto-restarts into the new version, matching the Windows one-click experience.

`pkexec` is guaranteed present by declaring `policykit-1` as an apt `Depends` in the `.deb` (apt resolves and installs it at `.deb` install time). It is not vendored: `pkexec` is a setuid-root system binary tied to `polkitd` and cannot live inside `/opt/clarifi`.

## Backend changes (`app.py`)

### 1. `api_version_check` / `_pick_release_assets`
- On Linux, compute `auto_install = bool(deb_url) and shutil.which('pkexec') is not None`.
- Keep returning `deb_url` / `deb_name` unchanged.
- If `pkexec` is absent (broken env, run-from-source), `auto_install` stays false and the UI falls back to the existing manual `.deb` link.

### 2. `api_version_download`
- Today rejects anything that is not `.exe` / `.msi`. Extend so that on Linux a `.deb` is accepted.
- Validation: URL is `https://`, host is GitHub (`github.com` or `objects.githubusercontent.com`), filename matches `clarifi_*.deb`.
- Reuse the existing `_DOWNLOAD_STATE` machinery and chunked download to `tempfile.gettempdir()`.

### 3. `api_version_install` (new Linux branch)
- **Security hardening (runs as root):** do not trust the client-supplied `path`. Only install the file our own download produced: compare against `_DOWNLOAD_STATE['path']`, confirm it is under tempdir and matches `clarifi_*.deb`.
- Run install in a background thread: `pkexec apt-get install -y <deb>`. Use `apt-get install` (not `dpkg -i`) so a future version that adds a dependency still resolves. `pkexec` triggers the polkit graphical password dialog (the calling process is in the user's session, so `polkitd` routes to the session's auth agent).
- Add `_INSTALL_STATE` + `GET /api/version/install/progress`, mirroring the download-progress pattern. States: `authorizing` → `installing` → `restarting` / `error`.
- **Success (returncode 0):** relaunch via the `clarifi` launcher with `start_new_session=True` (survives the parent exit), then `os._exit(0)` after a short delay so the HTTP response flushes. Window closes and reopens on the new version, same as Windows.
- **User cancel (pkexec returns 126):** clean message ("update cancelled"), app stays alive.
- **apt error (other non-zero):** error message in `_INSTALL_STATE`, app stays alive.

### Windows / macOS
- Windows: unchanged (existing `/SILENT` Inno flow).
- macOS: stays manual.

## Frontend changes (`templates/index.html`)

`checkForUpdates()` and the Updates panel already implement download + progress + install for Windows. The change is to lift the Windows-only gating:
- When `auto_install` is true (Windows **or** Linux), show the single "Download & Install" button with its progress bar.
- After download, poll `GET /api/version/install/progress` and show `Authorizing… / Installing… / Restarting…`.
- When `auto_install` is false on Linux (no pkexec), keep the existing manual `.deb` link.

## Build / release follow-ups (`release` branch, separate commit)

- Add `policykit-1` to the `Depends` line in `build-deb.sh`'s `control` block. This guarantees `pkexec` is present after install; the runtime `shutil.which` check becomes a defensive fallback only.
- Version bump: `APP_VERSION` (app.py) + `MyAppVersion` (ClariFi.iss) + matching `vX.Y.Z` tag, following the fixed push order: push `release`, then `main`, then the tag, then `gh release edit` to format the GitHub release.
- Update `CLAUDE.md` "Versioning & in-app updates": the updater is no longer Windows-only; document the Linux pkexec path and the `policykit-1` dependency.

## Out of scope (YAGNI)

- Custom polkit policy/action for a nicer dialog message. The default `org.freedesktop.policykit.exec` prompt is acceptable.
- AppImage / Flatpak repackaging (rootless updates). Noted as a possible future direction, not part of this work.
- macOS auto-update.

## Edge cases

- `pkexec` missing → `auto_install: false` → manual link (no regression).
- pkexec cancel (126) → "cancelled", non-fatal.
- apt dependency/network failure → surfaced error, app keeps running.
- Downloaded `.deb` equal/older version is never offered (gated by `update_available`).
