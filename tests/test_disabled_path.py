import importlib
import pytest

app = importlib.import_module('app')


@pytest.fixture
def tmp_data(tmp_path, monkeypatch):
    monkeypatch.delenv('CLARIFI_CLOUD_DSN', raising=False)
    monkeypatch.setattr(app, 'DATA_PATH', str(tmp_path / 'finance_data.xlsx'))
    return tmp_path


def test_init_and_save_use_local_file(tmp_data):
    app.init_data()
    assert (tmp_data / 'finance_data.xlsx').exists()
    wb = app._load_wb()
    wb['config'].append(['probe', 'hello'])
    app._save_wb(wb)
    wb2 = app._load_wb()
    rows = {r['key']: r['value'] for r in app._rows(wb2['config'])}
    assert rows.get('probe') == 'hello'


def test_save_wb_does_not_touch_network_when_disabled(tmp_data, monkeypatch):
    def _boom(*a, **k):
        raise AssertionError('_pg_connect must not be called when cloud is off')
    monkeypatch.setattr(app, '_pg_connect', _boom)
    app.init_data()
    wb = app._load_wb()
    app._save_wb(wb)  # must not raise
