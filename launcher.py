"""ClariFi desktop launcher.

Starts the Flask server on a random localhost port in a background thread,
then opens a native pywebview window pointing at it. The window is frameless
so the app can render its own (thicker, themed) title bar via HTML/CSS;
WindowApi exposes minimize/maximize/close to JavaScript.
"""
import os
import socket
import sys
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


class WindowApi:
    """Bridge exposed to the page as ``window.pywebview.api``."""

    def __init__(self):
        self._window = None
        self._maximized = False

    def attach(self, window):
        self._window = window

    def minimize(self):
        if self._window:
            self._window.minimize()

    def toggle_max(self):
        if not self._window:
            return
        if self._maximized:
            self._window.restore()
            self._maximized = False
        else:
            self._window.maximize()
            self._maximized = True

    def close_window(self):
        if self._window:
            self._window.destroy()


def main():
    init_data()
    port = _free_port()
    threading.Thread(target=_run_flask, args=(port,), daemon=True).start()
    url = f'http://127.0.0.1:{port}/'
    _wait_until_up(url)

    api = WindowApi()
    window = webview.create_window(
        'ClariFi',
        url,
        width=1280,
        height=820,
        min_size=(960, 640),
        frameless=True,
        easy_drag=False,
        js_api=api,
    )
    api.attach(window)

    def _on_loaded():
        try:
            window.evaluate_js("document.body.classList.add('desktop-app')")
        except Exception:
            pass

    window.events.loaded += _on_loaded
    webview.start()


if __name__ == '__main__':
    main()
