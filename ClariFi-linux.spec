# PyInstaller spec for ClariFi — Linux build.
# Build with: pyinstaller ClariFi-linux.spec
# Output: dist/ClariFi/ClariFi (onedir bundle, later wrapped into a .deb).
#
# Mirrors ClariFi.spec (the Windows build) but targets pywebview's Qt backend
# (PyQt5 + QtWebEngine) so the resulting bundle is self-contained and does not
# rely on system GTK/WebKit being installed. No .ico — PyInstaller ignores
# window icons on Linux; the .deb sets the menu icon via clarifi.desktop.

from PyInstaller.utils.hooks import collect_all, collect_submodules

block_cipher = None


# ── Bundle pillow-heif (HEIC / iPhone photo support) ─────────────────────────
# Optional: pillow-heif ships compiled libheif binaries, so it needs collect_all
# to pull its .so's too. If it isn't installed in the build env we skip it — the
# app still reads JPG/PNG/WEBP, it just can't decode iPhone HEIC photos directly.
def _heif_extras():
    try:
        datas, binaries, hiddenimports = collect_all('pillow_heif')
        return datas, binaries, hiddenimports
    except Exception as exc:
        print('NOTE: pillow-heif not bundled (%s). HEIC/iPhone photos will not '
              'decode in the installed app — run "pip install pillow-heif" in the '
              'build env to enable it.' % exc)
        return [], [], []


_heif_datas, _heif_binaries, _heif_hidden = _heif_extras()

# Pull QtWebEngine's data files (locales, the QtWebEngineProcess helper, ICU
# resources) — without these the embedded browser shows a blank window.
_qt_datas, _qt_binaries, _qt_hidden = collect_all('PyQt5.QtWebEngineWidgets')


a = Analysis(
    ['launcher.py'],
    pathex=[],
    binaries=_heif_binaries + _qt_binaries,
    datas=[
        ('templates', 'templates'),
    ] + _heif_datas + _qt_datas,
    hiddenimports=(
        collect_submodules('webview')
        + ['PIL', 'PIL.Image', 'pillow_heif', 'pypdf']
        + ['PyQt5', 'PyQt5.QtWebEngineWidgets', 'PyQt5.QtWebEngineCore']
        + _heif_hidden
        + _qt_hidden
    ),
    hookspath=[],
    runtime_hooks=[],
    excludes=['tkinter', 'unittest', 'pydoc', 'doctest'],
    cipher=block_cipher,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='ClariFi',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    name='ClariFi',
)
