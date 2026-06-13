# PyInstaller spec for ClariFi
# Build with: pyinstaller ClariFi.spec
# Output: dist/ClariFi/ClariFi.exe (and supporting files)

import os
from PyInstaller.utils.hooks import collect_all, collect_submodules

block_cipher = None


# ── Bundle the Tesseract OCR engine ──────────────────────────────────────────
# Receipt scanning shells out to tesseract.exe. Copy a full Tesseract install
# (binary + DLLs + tessdata) into a `tesseract/` subfolder of the bundle so the
# installed app works without the user installing Tesseract separately. At
# runtime app.py points pytesseract at this folder (see _configure_tesseract).
def _find_tesseract_dir():
    candidates = [os.environ.get('TESSERACT_DIR')]
    candidates += [
        r'C:\Program Files\Tesseract-OCR',
        r'C:\Program Files (x86)\Tesseract-OCR',
    ]
    for c in candidates:
        if c and os.path.isfile(os.path.join(c, 'tesseract.exe')):
            return c
    return None


def _tesseract_datas():
    tdir = _find_tesseract_dir()
    if not tdir:
        raise SystemExit(
            'Tesseract OCR not found. Install it (choco install tesseract, or the '
            'UB Mannheim build) or set the TESSERACT_DIR env var to a folder '
            'containing tesseract.exe before building.'
        )
    datas = []
    for root, _dirs, files in os.walk(tdir):
        rel = os.path.relpath(root, tdir)
        dest = 'tesseract' if rel == '.' else os.path.join('tesseract', rel)
        for name in files:
            datas.append((os.path.join(root, name), dest))
    return datas


# ── Bundle pillow-heif (HEIC / iPhone photo support) ─────────────────────────
# Optional: pillow-heif ships compiled libheif binaries, so it needs collect_all
# to pull its DLLs too. If it isn't installed in the build env we skip it — the
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


a = Analysis(
    ['launcher.py'],
    pathex=[],
    binaries=_heif_binaries,
    datas=[
        ('templates', 'templates'),
    ] + _tesseract_datas() + _heif_datas,
    hiddenimports=(
        collect_submodules('webview')
        + ['pytesseract', 'PIL', 'PIL.Image', 'pillow_heif']
        + _heif_hidden
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
    icon='clarifi.ico',
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
