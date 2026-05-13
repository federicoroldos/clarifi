"""ClariFi desktop launcher.

Starts the Flask server on a random localhost port in a background thread,
then opens a native window via pywebview pointing at it. Closing the window
exits the process (and the Flask thread with it, since it's a daemon).
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


def main():
    init_data()
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
