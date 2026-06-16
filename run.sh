#!/bin/sh
# ClariFi launcher (installed Linux package).
#
# Runs the app under the system Python 3 and the system WebKitGTK web view, so
# nothing heavy is bundled. Forces pywebview's GTK backend and keeps the user's
# spreadsheet in the XDG data dir (the app is installed read-only under /opt).
set -e

DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/ClariFi"
mkdir -p "$DATA_DIR"
export DATA_PATH="$DATA_DIR/finance_data.xlsx"

export PYWEBVIEW_GUI=gtk
export PYTHONPATH="/opt/clarifi:/opt/clarifi/vendor${PYTHONPATH:+:$PYTHONPATH}"

exec python3 /opt/clarifi/launcher.py
