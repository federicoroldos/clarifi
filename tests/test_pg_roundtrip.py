import os, importlib
import pytest
from openpyxl import Workbook

app = importlib.import_module('app')

DSN = os.environ.get('CLARIFI_CLOUD_DSN')
pytestmark = pytest.mark.skipif(not DSN, reason='set CLARIFI_CLOUD_DSN to run Postgres round-trip tests')


def _sample_wb():
    wb = Workbook()
    wb.remove(wb.active)
    for sheet, cols in app.SHEETS.items():
        ws = wb.create_sheet(sheet)
        ws.append(cols)
    wb['accounts'].append(['usd', 'US Dollar Account', 'usd', 1234.56,
                           '2026-06-16T10:00:00', False, '#32d74b'])
    wb['transactions'].append([1, '2026-06-16', 'Coffee', 4.5, 'Food', 'expense',
                               'usd', None, None, None])
    wb['config'].append(['ai_key', 'secret-value'])
    wb['fixed_payments'].append([1, 'Rent', 800.0, 'usd', 'Services', 5, 'expense'])
    wb['fixed_applied'].append([1, '2026-06'])
    return wb


def _clean(conn):
    cur = conn.cursor()
    for sheet in app.SHEETS:
        cur.execute('DROP TABLE IF EXISTS "%s"' % app._pg_table(sheet))
    conn.commit()


def test_roundtrip_preserves_rows_and_types():
    conn = app._pg_connect(DSN)
    _clean(conn)
    conn.close()
    os.environ['CLARIFI_CLOUD_DSN'] = DSN
    try:
        src = _sample_wb()
        app._pg_from_wb(src)
        out = app._wb_from_pg()
        for sheet in app.SHEETS:
            assert app._rows(out[sheet]) == app._rows(src[sheet]), sheet
        acc = app._rows(out['accounts'])[0]
        assert isinstance(acc['balance'], float) and acc['balance'] == 1234.56
        assert acc['archived'] is False
        txn = app._rows(out['transactions'])[0]
        assert isinstance(txn['id'], int) and txn['id'] == 1
    finally:
        os.environ.pop('CLARIFI_CLOUD_DSN', None)
