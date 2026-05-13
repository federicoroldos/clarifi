# Building the ClariFi installer

End result: a single `ClariFi-Setup-<version>.exe` your users double-click. No Python, no terminal on their end.

## One-time setup (your machine only)

1. **Python 3.13** (already installed).
2. **Build dependencies:**
   ```powershell
   pip install flask openpyxl pyinstaller pywebview
   ```
3. **Inno Setup 6**: download from <https://jrsoftware.org/isdl.php> and install with defaults.
4. **Icon file**: put a `clarifi.ico` in the project root. Quick way: take any 512×512 PNG and convert it at <https://icoconvert.com/> (export with multiple sizes embedded: 16, 32, 48, 256). Until you have one, both build steps will fail with "icon not found", so comment out the `icon=` lines in `ClariFi.spec` and `SetupIconFile=` in `ClariFi.iss` to build without it.

## Per-release build (3 commands)

```powershell
# 1. Bump version
#    - APP_VERSION in app.py
#    - MyAppVersion in ClariFi.iss
#    Keep them identical.

# 2. Bundle Python + Flask + your code into a folder of files
pyinstaller --noconfirm ClariFi.spec

# 3. Wrap that folder into a single setup .exe
& "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" ClariFi.iss
```

Result: `Output\ClariFi-Setup-0.1.0.exe`. That's the file you ship.

## Test the build locally

Before publishing:
```powershell
.\dist\ClariFi\ClariFi.exe          # smoke-test the bundled app
.\Output\ClariFi-Setup-0.1.0.exe    # smoke-test the installer
```
The installer drops files to `%LOCALAPPDATA%\Programs\ClariFi\` (non-admin install) and creates a Start Menu shortcut. Uninstall via Add/Remove Programs to verify cleanup.

## Publishing a release

```powershell
git tag v0.1.0
git push origin v0.1.0
```

Then on GitHub:
1. Releases → Draft a new release
2. Choose tag `v0.1.0`
3. Title: `ClariFi 0.1.0`
4. Drag `Output\ClariFi-Setup-0.1.0.exe` into the assets box
5. Publish

The in-app **Updates** tab will detect it automatically: the API picks the first `.exe` asset on the latest release.

## Where user data lives

- Installed app: `%LOCALAPPDATA%\Programs\ClariFi\` (or `Program Files\ClariFi\` if installed for all users)
- User data:    `%APPDATA%\ClariFi\finance_data.xlsx`

Uninstall preserves user data by default. To wipe it on uninstall, uncomment the `UninstallDelete` line at the bottom of `ClariFi.iss`.

## Known frictions

- **SmartScreen warning** on first launch (unsigned exe). User clicks "More info → Run anyway" once. To eliminate, buy an EV code-signing cert ($80–300/yr). Skip until you have a user base that complains.
- **Antivirus false positives** on PyInstaller bundles: rare but happens. If reported, submit the file to the AV vendor for whitelisting.
- **Bundle size** ~40–60 MB unpacked, ~15–25 MB compressed in the installer. Normal for Python apps.
