import importlib

app = importlib.import_module('app')

ASSETS = [
    {'name': 'ClariFi-Setup-0.2.0.exe', 'browser_download_url': 'https://x/ClariFi-Setup-0.2.0.exe'},
    {'name': 'clarifi_0.2.0_amd64.deb', 'browser_download_url': 'https://x/clarifi_0.2.0_amd64.deb'},
]


def test_windows_gets_auto_install():
    p = app._pick_release_assets(ASSETS, 'windows')
    assert p['auto_install'] is True
    assert p['installer_url'].endswith('.exe')
    assert p['deb_url'].endswith('.deb')


def test_linux_no_auto_install_but_has_deb():
    p = app._pick_release_assets(ASSETS, 'linux')
    assert p['auto_install'] is False
    assert p['deb_url'].endswith('.deb')
    assert p['deb_name'] == 'clarifi_0.2.0_amd64.deb'


def test_linux_without_exe_still_no_auto_install():
    deb_only = [ASSETS[1]]
    p = app._pick_release_assets(deb_only, 'linux')
    assert p['auto_install'] is False
    assert p['installer_url'] is None
    assert p['deb_url'].endswith('.deb')


def test_detect_os_returns_known_value():
    assert app._detect_os() in ('windows', 'linux', 'macos')
