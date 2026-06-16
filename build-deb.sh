#!/usr/bin/env bash
# Wrap the PyInstaller bundle (dist/ClariFi/) into a self-contained Debian
# package, the Linux counterpart of the Inno Setup .exe installer.
#
# Usage: bash build-deb.sh <version>      e.g. bash build-deb.sh 0.1.27
#
# Expects, relative to the repo root:
#   dist/ClariFi/        the PyInstaller onedir bundle (pyinstaller ClariFi-linux.spec)
#   clarifi.desktop      the menu entry
#   clarifi.png          256x256 icon (generated from clarifi.ico in CI)
#
# Produces: dist/clarifi_<version>_amd64.deb
set -euo pipefail

VERSION="${1:?usage: build-deb.sh <version>}"
ARCH="amd64"
PKG="clarifi"
ROOT="dist/deb"

rm -rf "$ROOT"
mkdir -p "$ROOT/DEBIAN" \
         "$ROOT/opt/clarifi" \
         "$ROOT/usr/bin" \
         "$ROOT/usr/share/applications" \
         "$ROOT/usr/share/icons/hicolor/256x256/apps"

# App bundle -> /opt/clarifi
cp -r dist/ClariFi/. "$ROOT/opt/clarifi/"

# Launchable from PATH and from the menu entry's Exec line
ln -sf /opt/clarifi/ClariFi "$ROOT/usr/bin/clarifi"
install -m 644 clarifi.desktop "$ROOT/usr/share/applications/clarifi.desktop"
install -m 644 clarifi.png "$ROOT/usr/share/icons/hicolor/256x256/apps/clarifi.png"

# QtWebEngine needs a handful of system libs that PyInstaller does not bundle.
# Names use alternatives where the Ubuntu 24.04 t64 rename applies, so the
# package stays installable on both 22.04 and 24.04+.
cat > "$ROOT/DEBIAN/control" <<EOF
Package: $PKG
Version: $VERSION
Section: utils
Priority: optional
Architecture: $ARCH
Maintainer: federicoroldos <fede212yt@gmail.com>
Depends: libnss3, libxcomposite1, libxdamage1, libxrandr2, libxkbcommon0, libgbm1, libasound2t64 | libasound2
Description: ClariFi personal finances tracker
 Multi-account personal finances tracker with transactions, fixed payments,
 transfers and receipt scanning. Runs fully offline in a native window.
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
