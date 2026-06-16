"""ClariFi desktop launcher.

Starts the Flask server on a random localhost port in a background thread,
then opens a native pywebview window pointing at it. The window uses the
standard Windows title bar — all native behaviors (drag, Aero Snap, edge
resize, taskbar click, double-click maximize) come from the OS for free.
"""
import os
import socket
import threading
import time
import urllib.request

import webview

from app import app, init_data


def _free_port():
    s = socket.socket()
    s.bind(('127.0.0.1', 0))
    port = s.getsockname()[1]
    s.close()
    return port


def _run_flask(port):
    app.run(host='127.0.0.1', port=port, debug=False, use_reloader=False, threaded=True)


def _wait_until_up(url, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            urllib.request.urlopen(url, timeout=1).read()
            return True
        except Exception:
            time.sleep(0.1)
    return False


def _set_gtk_app_identity():
    """Make the window's WM_CLASS match the .desktop file (Linux/GTK only).

    pywebview's GTK backend opens a plain Gtk.Window, so GTK derives WM_CLASS
    from the program name. Run via `python3 launcher.py`, that name is
    "launcher.py", which GNOME shows in the dock and fails to match against
    clarifi.desktop's StartupWMClass=ClariFi. Setting the program name to
    "ClariFi" before the window is realized fixes the dock label and lets
    GNOME group the window under the app's icon. No-op where gi is missing
    (e.g. Windows), so it is safe to call unconditionally.
    """
    if os.environ.get('PYWEBVIEW_GUI') != 'gtk':
        return
    try:
        from gi.repository import GLib
        GLib.set_prgname('ClariFi')
        GLib.set_application_name('ClariFi')
    except Exception:
        pass


def main():
    init_data()
    _set_gtk_app_identity()
    port = _free_port()
    threading.Thread(target=_run_flask, args=(port,), daemon=True).start()
    url = f'http://127.0.0.1:{port}/'
    _wait_until_up(url)

    webview.create_window(
        'ClariFi',
        url,
        width=1280,
        height=820,
        min_size=(960, 640),
    )
    webview.start()


if __name__ == '__main__':
    main()
