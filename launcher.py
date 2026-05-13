"""ClariFi desktop launcher.

Starts the Flask server on a random localhost port in a background thread,
then opens a native pywebview window pointing at it. The window is frameless
so the app can render its own (thicker, themed) title bar via HTML/CSS, but
on Windows we restore WS_THICKFRAME and subclass the window proc so every
native behavior (drag, Aero Snap, edge resize, taskbar click, double-click
maximize, snap layouts) works exactly like a normal Windows window.
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


# ── Win32 constants ─────────────────────────────────────────────────────────
GWL_STYLE          = -16
GWLP_WNDPROC       = -4
WS_THICKFRAME      = 0x00040000
WS_MINIMIZEBOX     = 0x00020000
WS_MAXIMIZEBOX     = 0x00010000
WS_CAPTION         = 0x00C00000
WS_SYSMENU         = 0x00080000

SWP_FRAMECHANGED   = 0x0020
SWP_NOMOVE         = 0x0002
SWP_NOSIZE         = 0x0001
SWP_NOZORDER       = 0x0004
SWP_NOOWNERZORDER  = 0x0200
SWP_NOACTIVATE     = 0x0010

WM_NCCALCSIZE      = 0x0083
WM_NCHITTEST       = 0x0084
WM_SIZE            = 0x0005

SIZE_RESTORED      = 0
SIZE_MAXIMIZED     = 2

HTCLIENT           = 1
HTCAPTION          = 2
HTLEFT             = 10
HTRIGHT            = 11
HTTOP              = 12
HTTOPLEFT          = 13
HTTOPRIGHT         = 14
HTBOTTOM           = 15
HTBOTTOMLEFT       = 16
HTBOTTOMRIGHT      = 17

SM_CXSIZEFRAME     = 32
SM_CYSIZEFRAME     = 33
SM_CXPADDEDBORDER  = 92

SW_MAXIMIZE        = 3
SW_RESTORE         = 9

# Layout of the HTML title bar (in CSS pixels, DPI-scaled at hit-test time).
TITLEBAR_HEIGHT_DIP = 38
BUTTONS_WIDTH_DIP   = 138   # 3 × 46 px window-control buttons on the right
RESIZE_BORDER_DIP   = 6


user32 = ctypes.windll.user32

# Set up signatures for the calls we make. Default ctypes assumptions are
# wrong for the pointer-returning ones on 64-bit Python.
if ctypes.sizeof(ctypes.c_void_p) == 8:
    _SetWindowLongPtr = user32.SetWindowLongPtrW
    _GetWindowLongPtr = user32.GetWindowLongPtrW
else:
    _SetWindowLongPtr = user32.SetWindowLongW
    _GetWindowLongPtr = user32.GetWindowLongW
_SetWindowLongPtr.argtypes = [wintypes.HWND, ctypes.c_int, ctypes.c_void_p]
_SetWindowLongPtr.restype  = ctypes.c_void_p
_GetWindowLongPtr.argtypes = [wintypes.HWND, ctypes.c_int]
_GetWindowLongPtr.restype  = ctypes.c_void_p

user32.CallWindowProcW.argtypes = [ctypes.c_void_p, wintypes.HWND, ctypes.c_uint, wintypes.WPARAM, wintypes.LPARAM]
user32.CallWindowProcW.restype  = ctypes.c_long
user32.DefWindowProcW.argtypes  = [wintypes.HWND, ctypes.c_uint, wintypes.WPARAM, wintypes.LPARAM]
user32.DefWindowProcW.restype   = ctypes.c_long
user32.GetSystemMetrics.argtypes = [ctypes.c_int]
user32.GetSystemMetrics.restype  = ctypes.c_int
user32.IsZoomed.argtypes = [wintypes.HWND]
user32.IsZoomed.restype  = wintypes.BOOL


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
        return int(user32.GetForegroundWindow())
    except Exception:
        return None


class _RECT(ctypes.Structure):
    _fields_ = [('left', wintypes.LONG), ('top', wintypes.LONG),
                ('right', wintypes.LONG), ('bottom', wintypes.LONG)]


class _NCCALCSIZE_PARAMS(ctypes.Structure):
    _fields_ = [('rgrc', _RECT * 3), ('lppos', ctypes.c_void_p)]


def _dpi_scale(hwnd):
    try:
        return user32.GetDpiForWindow(hwnd) / 96.0
    except Exception:
        return 1.0


WNDPROC = ctypes.WINFUNCTYPE(
    ctypes.c_long, wintypes.HWND, ctypes.c_uint, wintypes.WPARAM, wintypes.LPARAM
)

# hwnd -> {'old': old_wndproc_ptr, 'new': WNDPROC instance, 'window': pywebview window, 'maxed': bool}
_subclass_state = {}


def _eval_async(window, js):
    """Run evaluate_js off the wndproc thread to avoid reentrancy."""
    def _go():
        try:
            window.evaluate_js(js)
        except Exception:
            pass
    threading.Thread(target=_go, daemon=True).start()


def _notify_max_state(state, is_max):
    if state.get('maxed') == is_max:
        return
    state['maxed'] = is_max
    win = state.get('window')
    if win is not None:
        flag = 'true' if is_max else 'false'
        _eval_async(win, f"window._onWinMaxChanged && window._onWinMaxChanged({flag})")


def _make_wndproc(state):
    """Build the subclassed window procedure for this hwnd."""

    def wndproc(hwnd, msg, wparam, lparam):
        if msg == WM_NCCALCSIZE and wparam:
            # Remove the non-client frame entirely so our HTML title bar
            # sits flush at the top.
            if user32.IsZoomed(hwnd):
                # When maximized, Windows extends the window past the work
                # area by the size of the resize frame. Inset by that amount
                # so content stays visible and the taskbar is uncovered.
                params = ctypes.cast(lparam, ctypes.POINTER(_NCCALCSIZE_PARAMS)).contents
                border = user32.GetSystemMetrics(SM_CXSIZEFRAME) + \
                         user32.GetSystemMetrics(SM_CXPADDEDBORDER)
                params.rgrc[0].left   += border
                params.rgrc[0].right  -= border
                params.rgrc[0].top    += border
                params.rgrc[0].bottom -= border
            return 0

        if msg == WM_NCHITTEST:
            x = ctypes.c_short(lparam & 0xFFFF).value
            y = ctypes.c_short((lparam >> 16) & 0xFFFF).value
            rect = _RECT()
            user32.GetWindowRect(hwnd, ctypes.byref(rect))
            scale = _dpi_scale(hwnd)
            tb     = int(TITLEBAR_HEIGHT_DIP * scale)
            btn_w  = int(BUTTONS_WIDTH_DIP   * scale)
            border = int(RESIZE_BORDER_DIP   * scale)
            maxed  = bool(user32.IsZoomed(hwnd))

            # Edge resize handles are off-limits when maximized.
            on_left   = (not maxed) and x <  rect.left   + border
            on_right  = (not maxed) and x >= rect.right  - border
            on_top    = (not maxed) and y <  rect.top    + border
            on_bottom = (not maxed) and y >= rect.bottom - border

            if on_top and on_left:     return HTTOPLEFT
            if on_top and on_right:    return HTTOPRIGHT
            if on_bottom and on_left:  return HTBOTTOMLEFT
            if on_bottom and on_right: return HTBOTTOMRIGHT
            if on_top:                 return HTTOP
            if on_bottom:              return HTBOTTOM
            if on_left:                return HTLEFT
            if on_right:                return HTRIGHT

            # Top strip = drag caption, except the trailing buttons cluster.
            if y < rect.top + tb:
                if x >= rect.right - btn_w:
                    return HTCLIENT
                return HTCAPTION
            return HTCLIENT

        if msg == WM_SIZE:
            if wparam == SIZE_MAXIMIZED:
                _notify_max_state(state, True)
            elif wparam == SIZE_RESTORED:
                _notify_max_state(state, False)

        return user32.CallWindowProcW(state['old'], hwnd, msg, wparam, lparam)

    return WNDPROC(wndproc)


def _apply_native_chrome(hwnd, window):
    """Restore native Windows behavior on a frameless pywebview window."""
    style = _GetWindowLongPtr(hwnd, GWL_STYLE) or 0
    new_style = style | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU
    _SetWindowLongPtr(hwnd, GWL_STYLE, new_style)

    state = {'old': None, 'new': None, 'window': window, 'maxed': False}
    new_proc = _make_wndproc(state)
    state['new'] = new_proc  # keep the callback alive
    old_proc = _SetWindowLongPtr(hwnd, GWLP_WNDPROC, ctypes.cast(new_proc, ctypes.c_void_p).value)
    state['old'] = old_proc
    _subclass_state[hwnd] = state

    # Force the OS to recompute the frame so WM_NCCALCSIZE fires immediately.
    user32.SetWindowPos(
        hwnd, 0, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE |
        SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_NOACTIVATE,
    )


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

    def attach(self, window):
        self._window = window

    def minimize(self):
        if self._window:
            self._window.minimize()

    def toggle_max(self):
        if not self._window:
            return False
        if os.name == 'nt':
            hwnd = _native_hwnd(self._window)
            if hwnd:
                if user32.IsZoomed(hwnd):
                    user32.ShowWindow(hwnd, SW_RESTORE)
                    return False
                user32.ShowWindow(hwnd, SW_MAXIMIZE)
                return True
        # Non-Windows fallback (untested; this app currently ships only on Windows).
        try:
            self._window.maximize()
            return True
        except Exception:
            return False

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
        if os.name == 'nt':
            # Apply the native-chrome subclass after the BrowserView's own
            # window proc is in place. Wait a moment so the HWND is stable.
            def _setup():
                time.sleep(0.2)
                hwnd = _native_hwnd(window)
                if hwnd:
                    try:
                        _apply_native_chrome(hwnd, window)
                    except Exception:
                        pass
            threading.Thread(target=_setup, daemon=True).start()

    window.events.loaded += _on_loaded
    webview.start()


if __name__ == '__main__':
    main()
