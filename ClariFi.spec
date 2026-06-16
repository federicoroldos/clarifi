# PyInstaller spec for ClariFi
# Build with: pyinstaller ClariFi.spec
# Output: dist/ClariFi/ClariFi.exe (and supporting files)

from PyInstaller.utils.hooks import collect_all, collect_submodules

block_cipher = None


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


# ── Bundle pg8000 (optional cloud Postgres sync) ─────────────────────────────
# pg8000 is imported lazily (inside _pg_connect), so PyInstaller's static scan
# misses it — it must be a hiddenimport. Its SCRAM auth path (used by hosted
# Postgres like Supabase/Neon) pulls in scramp + asn1crypto. All pure Python.
def _pg_extras():
    try:
        return (collect_submodules('pg8000')
                + collect_submodules('scramp')
                + ['asn1crypto'])
    except Exception as exc:
        print('NOTE: pg8000 not bundled (%s). Cloud sync will be unavailable in '
              'the installed app — run "pip install pg8000" in the build env.' % exc)
        return []


_pg_hidden = _pg_extras()


a = Analysis(
    ['launcher.py'],
    pathex=[],
    binaries=_heif_binaries,
    datas=[
        ('templates', 'templates'),
    ] + _heif_datas,
    hiddenimports=(
        collect_submodules('webview')
        + ['PIL', 'PIL.Image', 'pillow_heif', 'pypdf']
        + _heif_hidden
        + _pg_hidden
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
