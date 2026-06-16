import json, importlib, os
import pytest

app = importlib.import_module('app')


@pytest.fixture
def tmp_data(tmp_path, monkeypatch):
    monkeypatch.delenv('CLARIFI_CLOUD_DSN', raising=False)
    data_path = str(tmp_path / 'finance_data.xlsx')
    monkeypatch.setattr(app, 'DATA_PATH', data_path)
    return tmp_path


def test_cloud_inactive_when_no_config(tmp_data):
    assert app.cloud_active() is False
    assert app._cloud_dsn() is None


def test_cloud_active_after_write(tmp_data):
    app._write_cloud_config({'enabled': True, 'dsn': 'postgresql://u:p@h:5432/db'})
    assert app.cloud_active() is True
    assert app._cloud_dsn() == 'postgresql://u:p@h:5432/db'


def test_disabled_flag_keeps_dsn_but_inactive(tmp_data):
    app._write_cloud_config({'enabled': False, 'dsn': 'postgresql://u:p@h:5432/db'})
    assert app.cloud_active() is False


def test_env_override_forces_active(tmp_data, monkeypatch):
    monkeypatch.setenv('CLARIFI_CLOUD_DSN', 'postgresql://env:env@h:5432/db')
    assert app.cloud_active() is True
    assert app._cloud_dsn() == 'postgresql://env:env@h:5432/db'
