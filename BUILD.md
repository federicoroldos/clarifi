# Building ClariFi

ClariFi ships as a single Windows installer, `Output\ClariFi-Setup-<version>.exe`, that
bundles Python, Flask, openpyxl and the app code. End users do not need Python installed.

The build runs in two stages: PyInstaller bundles the app into a folder of binaries, then
Inno Setup wraps that folder into the installer.

## Branch model

App source and build tooling live on different branches:

- **`main`**: the app itself (`app.py`, `templates/index.html`, `README.md`, `CLAUDE.md`,
  `.github/workflows/release.yml`).
- **`build`**: the desktop/installer files (`launcher.py`, `ClariFi.spec`, `ClariFi.iss`,
  `clarifi.ico`, this `BUILD.md`).

The CI workflow checks out `main` for the app and pulls the four build files from
`origin/build`, so a release needs both branches up to date before the tag is pushed.

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

## CI release (the normal path)

`.github/workflows/release.yml` runs the whole pipeline on `windows-latest` whenever a
`v*` tag is pushed. It resolves the version from the tag, installs the dependencies, runs
both stages, and attaches the installer to the matching GitHub Release.

`workflow_dispatch` is also available for a manual run; it uploads the installer as a
workflow artifact instead of creating a release.

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
