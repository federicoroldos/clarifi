"""ClariFi desktop launcher.

Starts the Flask server on a random localhost port in a background thread,
then opens a native pywebview window pointing at it. The window is frameless
so the app can render its own (thicker, themed) title bar via HTML/CSS;
WindowApi exposes minimize/maximize/close to JavaScript.
"""
import ctypes
from ctypes import wintypes
import os
import socket
import sys
import threading
import time
import urllib.request

import webview

from app import app, init_data


def _native_hwnd(window):
    """Best-effort to get the Win32 HWND of a pywebview window."""
    if os.name != 'nt' or window is None:
        return None
    try:
        from webview.platforms.winforms import BrowserView
        form = BrowserView.instances.get(window.uid)
        if form is not None:
            return int(form.Handle.ToInt64())
    except Exception:
        pass
    try:
        from webview.platforms.edgechromium import BrowserView as ECBrowserView
        form = ECBrowserView.instances.get(window.uid)
        if form is not None:
            return int(form.Handle.ToInt64())
    except Exception:
        pass
    try:
        return int(ctypes.windll.user32.GetForegroundWindow())
    except Exception:
        return None


class _RECT(ctypes.Structure):
    _fields_ = [('left', wintypes.LONG), ('top', wintypes.LONG),
                ('right', wintypes.LONG), ('bottom', wintypes.LONG)]


class _MONITORINFO(ctypes.Structure):
    _fields_ = [('cbSize', wintypes.DWORD), ('rcMonitor', _RECT),
                ('rcWork', _RECT), ('dwFlags', wintypes.DWORD)]


def _get_window_rect(hwnd):
    rect = _RECT()
    if not ctypes.windll.user32.GetWindowRect(hwnd, ctypes.byref(rect)):
        return None
    return (rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)


def _maximize_to_work_area(hwnd, currently_maxed, restore_rect):
    """Maximize/restore by sizing to the monitor work area.

    pywebview's maximize() on a frameless window omits the WS_THICKFRAME the
    OS needs to clip max bounds, so it covers the taskbar (looks like
    fullscreen). Resizing to the work area manually keeps the taskbar visible.
    Returns True if it handled the action.
    """
    user32 = ctypes.windll.user32
    SWP_NOZORDER = 0x0004
    SWP_SHOWWINDOW = 0x0040
    MONITOR_DEFAULTTONEAREST = 2

    if currently_maxed:
        if not restore_rect:
            return False
        x, y, w, h = restore_rect
        user32.SetWindowPos(hwnd, 0, x, y, w, h, SWP_NOZORDER | SWP_SHOWWINDOW)
        return True

    hmon = user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST)
    if not hmon:
        return False
    mi = _MONITORINFO()
    mi.cbSize = ctypes.sizeof(_MONITORINFO)
    if not user32.GetMonitorInfoW(hmon, ctypes.byref(mi)):
        return False
    work = mi.rcWork
    user32.SetWindowPos(
        hwnd, 0,
        work.left, work.top,
        work.right - work.left, work.bottom - work.top,
        SWP_NOZORDER | SWP_SHOWWINDOW,
    )
    return True


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
        self._restore_rect = None  # (x, y, w, h) saved before maximizing

    def attach(self, window):
        self._window = window

    def minimize(self):
        if self._window:
            self._window.minimize()

    def start_drag(self):
        """Begin a native window drag from the custom title bar.

        pywebview's ``pywebview-drag-region`` attribute is unreliable on the
        EdgeChromium backend, so we trigger the Win32 caption drag manually:
        release the WebView's mouse capture, then post WM_NCLBUTTONDOWN with
        HTCAPTION so the OS takes over moving the window.
        """
        if os.name != 'nt' or not self._window:
            return
        hwnd = _native_hwnd(self._window)
        if not hwnd:
            return
        WM_NCLBUTTONDOWN = 0x00A1
        HTCAPTION = 2
        user32 = ctypes.windll.user32
        user32.ReleaseCapture()
        user32.SendMessageW(hwnd, WM_NCLBUTTONDOWN, HTCAPTION, 0)

    def toggle_max(self):
        if not self._window:
            return self._maximized
        if os.name == 'nt':
            hwnd = _native_hwnd(self._window)
            if hwnd:
                # Capture the pre-maximize rect BEFORE resizing — otherwise
                # _get_window_rect would return the already-maximized bounds.
                pre_rect = _get_window_rect(hwnd) if not self._maximized else None
                if _maximize_to_work_area(hwnd, self._maximized, self._restore_rect):
                    if self._maximized:
                        self._maximized = False
                    else:
                        self._restore_rect = pre_rect or self._restore_rect
                        self._maximized = True
                    return self._maximized
        if self._maximized:
            self._window.restore()
            self._maximized = False
        else:
            self._window.maximize()
            self._maximized = True
        return self._maximized

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
