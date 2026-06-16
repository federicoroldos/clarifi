#!/usr/bin/env bash
# Build a thin Debian package for ClariFi.
#
# Unlike the Windows installer (which bundles Python via PyInstaller), this
# package ships only the app source plus a small set of vendored pip-only
# pure-Python deps, and relies on the system Python and the system WebKitGTK
# web view. Everything available in the Ubuntu repos is declared as apt
# Depends, so `apt install ./clarifi_*.deb` pulls it in. Keeps the .deb tiny.
#
# Usage: bash build-deb.sh <version>      e.g. bash build-deb.sh 0.1.29
#
# Expects, relative to the repo root:
#   app.py, launcher.py, run.sh   the app and its launcher
#   templates/                    the frontend
#   vendor/                       pip install --target vendor pywebview pypdf
#   clarifi.desktop               the menu entry
#   clarifi.png                   256x256 icon (generated from clarifi.ico in CI)
#
# Produces: dist/clarifi_<version>_amd64.deb
set -euo pipefail

VERSION="${1:?usage: build-deb.sh <version>}"
ARCH="amd64"
PKG="clarifi"
ROOT="dist/deb"
APPDIR="$ROOT/opt/clarifi"

rm -rf "$ROOT"
mkdir -p "$ROOT/DEBIAN" \
         "$APPDIR" \
         "$ROOT/usr/bin" \
         "$ROOT/usr/share/applications" \
         "$ROOT/usr/share/icons/hicolor/256x256/apps"

# App source + vendored pure-Python deps -> /opt/clarifi
cp app.py launcher.py run.sh "$APPDIR/"
cp -r templates "$APPDIR/templates"
cp -r vendor "$APPDIR/vendor"
chmod 755 "$APPDIR/run.sh"

# Launchable from PATH and from the menu entry's Exec line
ln -sf /opt/clarifi/run.sh "$ROOT/usr/bin/clarifi"
install -m 644 clarifi.desktop "$ROOT/usr/share/applications/clarifi.desktop"
install -m 644 clarifi.png "$ROOT/usr/share/icons/hicolor/256x256/apps/clarifi.png"

# apt resolves all of these. The webkit2 typelib uses an alternative so the
# package installs on both Ubuntu 22.04 (4.0) and 24.04+ (4.1). pywebview's
# GTK backend needs python3-gi(-cairo) + the GTK and WebKit2 typelibs; the rest
# are the app's own runtime deps (Flask, openpyxl, Pillow for receipt scans,
# pg8000 for the optional cloud Postgres sync).
cat > "$ROOT/DEBIAN/control" <<EOF
Package: $PKG
Version: $VERSION
Section: utils
Priority: optional
Architecture: $ARCH
Maintainer: federicoroldos <fede212yt@gmail.com>
Depends: python3 (>= 3.9), python3-gi, python3-gi-cairo, gir1.2-gtk-3.0, gir1.2-webkit2-4.1 | gir1.2-webkit2-4.0, python3-flask, python3-openpyxl, python3-pil, python3-pg8000
Description: ClariFi personal finances tracker
 Multi-account personal finances tracker with transactions, fixed payments,
 transfers and receipt scanning. Runs fully offline in a native window using
 the system WebKitGTK web view.
EOF

# Refresh the desktop/icon caches so the app shows up in the menu immediately.
cat > "$ROOT/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q /usr/share/applications || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
fi
EOF
chmod 755 "$ROOT/DEBIAN/postinst"

dpkg-deb --root-owner-group --build "$ROOT" "dist/${PKG}_${VERSION}_${ARCH}.deb"
echo "Built dist/${PKG}_${VERSION}_${ARCH}.deb"
