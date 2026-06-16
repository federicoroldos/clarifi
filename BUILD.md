# Building ClariFi

ClariFi ships as a single Windows installer, `Output\ClariFi-Setup-<version>.exe`, plus a
Linux package, `clarifi_<version>_amd64.deb`. Both bundle Python, Flask, openpyxl and the
app code, so end users do not need Python installed. They are attached to the same GitHub
Release.

The Windows build runs in two stages: PyInstaller bundles the app into a folder of binaries,
then Inno Setup wraps that folder into the installer. The Linux build is analogous:
PyInstaller (Qt backend) produces the bundle, then `build-deb.sh` wraps it into the `.deb`.

## Branch model

App source and build tooling live on different branches:

- **`main`**: the app itself (`app.py`, `templates/index.html`, `README.md`, `CLAUDE.md`,
  `.github/workflows/release.yml`).
- **`build`**: the desktop/installer files. Windows: `launcher.py`, `ClariFi.spec`,
  `ClariFi.iss`, `clarifi.ico`. Linux: `ClariFi-linux.spec`, `clarifi.desktop`,
  `build-deb.sh` (`launcher.py` and `clarifi.ico` are shared). Plus this `BUILD.md`.

The CI workflow checks out `main` for the app and pulls the build files from `origin/build`,
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

The Linux package is self-contained the same way the Windows installer is: PyInstaller
bundles Python and the app, using pywebview's **Qt** backend (`PyQt5` + `PyQtWebEngine`)
so the bundle does not depend on system GTK/WebKit being present.

```bash
pip install flask openpyxl pyinstaller pywebview PyQt5 PyQtWebEngine pillow pillow-heif pypdf
python -c "from PIL import Image; Image.open('clarifi.ico').resize((256,256)).convert('RGBA').save('clarifi.png')"
pyinstaller --noconfirm ClariFi-linux.spec
bash build-deb.sh <version>
```

Output: `dist/clarifi_<version>_amd64.deb`. Installing it (`sudo apt install ./clarifi_*.deb`)
drops the bundle in `/opt/clarifi`, adds a `clarifi` command on `PATH`, and registers the
menu entry from `clarifi.desktop` so ClariFi appears in the applications menu. The package
declares the few QtWebEngine runtime libs as `Depends`, which `apt` resolves automatically.
User data lives in `~/.local/share/ClariFi/` (the XDG branch of `_default_data_path()`).

This build must run **on Linux** — PyInstaller does not cross-compile.

## CI release (the normal path)

`.github/workflows/release.yml` builds both packages whenever a `v*` tag is pushed. The
`build` job runs on `windows-latest` (PyInstaller + Inno Setup), then `build-linux` runs on
`ubuntu-latest` (PyInstaller + `build-deb.sh`). Both resolve the version from the tag and
attach their package to the same GitHub Release; `build-linux` runs after `build` so the two
do not race to create the release.

`workflow_dispatch` is also available for a manual run; it uploads both packages as workflow
artifacts instead of creating a release.

## Release checklist

For a release that bumps the version (the usual case), keep these in lockstep:

1. Bump `APP_VERSION` in `app.py` (`main`) and `#define MyAppVersion` in `ClariFi.iss`
   (`build`). They must match.
2. Commit and push **`build`** first, then **`main`**.
3. Create the `vX.Y.Z` tag on the `main` commit and push the tag. This triggers the build.
4. After the build publishes the release, set the title and notes with
   `gh release edit vX.Y.Z --title "ClariFi X.Y.Z" --notes "..."` (the workflow does not
   format these for you).

Pushing the tag before `build` is up to date ships an installer with the old version.
