# Building ClariFi

ClariFi ships as a single Windows installer, `Output\ClariFi-Setup-<version>.exe`, plus a
Linux package, `clarifi_<version>_amd64.deb`. Both are attached to the same GitHub Release.

The two builds take different shapes on purpose:

- **Windows** bundles Python and the app with PyInstaller, then Inno Setup wraps that folder
  into the installer. The web view comes from the system (Edge WebView2), so nothing heavy
  is bundled.
- **Linux** is a thin package: it does not bundle Python or a web engine. It ships the app
  source plus a small set of vendored pip-only pure-Python deps (`pywebview`, `pypdf`), and
  declares the rest (Python, GTK/WebKitGTK, Flask, Pillow, openpyxl) as apt `Depends` so
  `apt install` resolves them. This keeps the `.deb` at a few MB and reuses the system
  WebKitGTK, the Linux equivalent of how Windows reuses WebView2.

## Branch model

App source and build tooling live on different branches:

- **`main`**: the app itself (`app.py`, `templates/index.html`, `README.md`, `CLAUDE.md`,
  `.github/workflows/release.yml`).
- **`release`**: the desktop/installer files. Windows: `launcher.py`, `ClariFi.spec`,
  `ClariFi.iss`, `clarifi.ico`. Linux: `clarifi.desktop`, `run.sh`, `build-deb.sh`
  (`launcher.py` and `clarifi.ico` are shared). Plus this `BUILD.md`.

The CI workflow checks out `main` for the app and pulls the build files from `origin/release`,
so a release needs both branches up to date before the tag is pushed.

## Prerequisites (local build)

- Python 3.13
- `pip install flask openpyxl pyinstaller pywebview pillow pillow-heif pypdf`
- [Inno Setup 6](https://jrsoftware.org/isdl.php)

`pillow-heif` is optional; without it the installed app still reads JPG/PNG/WEBP, it just
cannot decode HEIC (iPhone) photos.

## Stage 1: PyInstaller bundle

From the build files (with `app.py` and `templates/` available next to them):

```bash
pyinstaller --noconfirm ClariFi.spec
```

Output: `dist\ClariFi\ClariFi.exe` plus its supporting DLLs and `.pyd` files. `ClariFi.exe`
is the app, but it needs the whole surrounding folder to run. The entry point is
`launcher.py` (not `app.py`): it starts Flask on a random localhost port in a daemon thread,
waits for it to answer, then opens a native pywebview window pointing at it.

## Stage 2: Inno Setup installer

```bash
"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" ClariFi.iss
```

Output: `Output\ClariFi-Setup-<version>.exe`. The version comes from
`#define MyAppVersion` near the top of `ClariFi.iss`.

## Linux .deb build

The Linux package is thin: it bundles neither Python nor a web engine. It ships the app
source plus a small `vendor/` of pip-only pure-Python deps, and leans on the system Python
and the system WebKitGTK. The build just gathers those pieces; no compilation is involved,
so it can even be assembled on a non-Linux box (only `dpkg-deb` is required to pack it).

```bash
# from a checkout that has app.py, templates/ (main) and launcher.py, clarifi.ico (build)
pip install --target vendor pywebview pypdf
python -c "from PIL import Image; Image.open('clarifi.ico').resize((256,256)).convert('RGBA').save('clarifi.png')"
bash build-deb.sh <version>
```

Output: `dist/clarifi_<version>_amd64.deb`. Installing it (`sudo apt install ./clarifi_*.deb`)
drops the app in `/opt/clarifi`, adds a `clarifi` command on `PATH` (a symlink to `run.sh`),
and registers the menu entry from `clarifi.desktop` so ClariFi appears in the applications
menu. `apt` pulls the declared `Depends` (Python, GTK/WebKitGTK typelibs, Flask, openpyxl,
Pillow). `run.sh` forces pywebview's GTK backend, puts `/opt/clarifi` and its `vendor/` on
`PYTHONPATH`, and points `DATA_PATH` at `~/.local/share/ClariFi/finance_data.xlsx` (the app
is installed read-only under `/opt`, so user data must live in the XDG data dir).

The `webkit2` typelib is declared with an alternative (`gir1.2-webkit2-4.1 | ...-4.0`) so the
package installs on both Ubuntu 22.04 and 24.04+.

## CI release (the normal path)

`.github/workflows/release.yml` builds both packages whenever a `v*` tag is pushed. The
`release-windows` job runs on `windows-latest` (PyInstaller + Inno Setup), then
`release-linux` runs on `ubuntu-latest` (vendor deps + `build-deb.sh`). Both resolve the
version from the tag and attach their package to the same GitHub Release; `release-linux`
runs after `release-windows` so the two do not race to create the release.

`workflow_dispatch` is also available for a manual run; it uploads both packages as workflow
artifacts instead of creating a release.

## Release checklist

For a release that bumps the version (the usual case), keep these in lockstep:

1. Bump `APP_VERSION` in `app.py` (`main`) and `#define MyAppVersion` in `ClariFi.iss`
   (`release`). They must match.
2. Commit and push **`release`** first, then **`main`**.
3. Create the `vX.Y.Z` tag on the `main` commit and push the tag. This triggers the build.
4. After the build publishes the release, set the title and notes with
   `gh release edit vX.Y.Z --title "ClariFi X.Y.Z" --notes "..."` (the workflow does not
   format these for you).

Pushing the tag before `release` is up to date ships packages with the old version.
