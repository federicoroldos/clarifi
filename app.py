from flask import Flask, jsonify, request, render_template, Response
from datetime import datetime, timedelta
from openpyxl import Workbook, load_workbook
from threading import Lock
import os, sys, secrets, json, urllib.request, urllib.error

APP_VERSION = '0.1.3'
GITHUB_REPO = 'federicoroldos/basic-personal-finances-tracker'


def _default_data_path():
    if os.environ.get('DATA_PATH'):
        return os.environ['DATA_PATH']
    if getattr(sys, 'frozen', False):
        base = os.path.join(os.environ.get('APPDATA') or os.path.expanduser('~'), 'ClariFi')
        os.makedirs(base, exist_ok=True)
        return os.path.join(base, 'finance_data.xlsx')
    return 'finance_data.xlsx'

app = Flask(__name__)
app.secret_key = os.environ.get('SECRET_KEY', secrets.token_hex(32))
app.config['TEMPLATES_AUTO_RELOAD'] = True
DATA_PATH  = _default_data_path()
XLSX_LOCK  = Lock()
CATEGORIES = ['Supermarket','Food','Transport','Games','Services','Health','Others']
CURRENCIES = {
    'krw': {'name': 'Korean Won',    'symbol': '₩',   'decimals': 0},
    'uyu': {'code': 'UYU', 'name': 'Uruguayan Peso', 'symbol': '$U',  'decimals': 2},
    'usd': {'code': 'USD', 'name': 'US Dollar',      'symbol': 'US$', 'decimals': 2},
}
CURRENCIES['krw'].update({'code': 'KRW', 'symbol': '₩'})
LEGACY_ACCOUNT_BANKS = {
    'krw': 'Korean Won Account',
    'uyu': 'Uruguayan Peso Account',
    'usd': 'US Dollar Account',
}

# XLSX storage
SHEETS = {
    'config': ['key', 'value'],
    'accounts': ['id', 'bank', 'currency', 'balance', 'created_at', 'archived', 'color'],
    'transactions': ['id', 'date', 'description', 'amount', 'category', 'type', 'account'],
    'fixed_payments': ['id', 'name', 'amount', 'account', 'category', 'day'],
    'fixed_applied': ['payment_id', 'year_month'],
}

def _headers(ws):
    return [c.value for c in ws[1]]

def _ensure_headers(ws, expected):
    if _headers(ws)[:len(expected)] != expected:
        for idx, name in enumerate(expected, start=1):
            ws.cell(row=1, column=idx, value=name)

def _rows(ws):
    headers = _headers(ws)
    out = []
    for values in ws.iter_rows(min_row=2, values_only=True):
        if not any(v is not None for v in values):
            continue
        out.append({headers[i]: values[i] if i < len(values) else None for i in range(len(headers))})
    return out

def _next_id(ws):
    ids = []
    for row in _rows(ws):
        try:
            ids.append(int(row.get('id') or 0))
        except (TypeError, ValueError):
            pass
    return max(ids, default=0) + 1

def _load_wb():
    return load_workbook(DATA_PATH)


# ── ROUTES ────────────────────────────────────────────────────────────────────
@app.route('/')
def index():
    return render_template('index.html', app_version=APP_VERSION)


@app.route('/favicon.ico')
def favicon():
    svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="#7a5cf2"/><text x="16" y="22" font-family="Arial,Helvetica,sans-serif" font-size="16" font-weight="700" fill="white" text-anchor="middle">C</text></svg>'
    return Response(svg, mimetype='image/svg+xml')

@app.route('/api/summary')
def api_summary(): return jsonify(build_summary())

@app.route('/api/transactions')
def api_transactions():
    with XLSX_LOCK:
        wb = _load_wb()
        txns = _rows(wb['transactions'])
    txns.sort(key=lambda t: (str(t.get('date') or ''), int(t.get('id') or 0)), reverse=True)
    return jsonify(txns)

@app.route('/api/fund',    methods=['POST'])
def add_fund():    return _add_txn(request.json, 'fund')

@app.route('/api/expense', methods=['POST'])
def add_expense(): return _add_txn(request.json, 'expense')





# fixed payments


@app.route('/api/fixed/<int:fid>', methods=['DELETE'])
def delete_fixed(fid):
    with XLSX_LOCK:
        wb = _load_wb()
        fixed_ws = wb['fixed_payments']
        for row_idx in range(fixed_ws.max_row, 1, -1):
            if int(fixed_ws.cell(row=row_idx, column=1).value or 0) == fid:
                fixed_ws.delete_rows(row_idx, 1)

        applied_ws = wb['fixed_applied']
        for row_idx in range(applied_ws.max_row, 1, -1):
            if int(applied_ws.cell(row=row_idx, column=1).value or 0) == fid:
                applied_ws.delete_rows(row_idx, 1)
        wb.save(DATA_PATH)
    return jsonify({'ok': True})

@app.route('/api/fixed/<int:fid>/apply', methods=['POST'])
def apply_fixed(fid):
    this_month = datetime.now().strftime('%Y-%m')
    today_str  = datetime.now().strftime('%Y-%m-%d')
    with XLSX_LOCK:
        wb = _load_wb()
        fixed = _rows(wb['fixed_payments'])
        fp = next((row for row in fixed if int(row.get('id') or 0) == fid), None)
        if not fp:
            return jsonify({'ok': False}), 404

        applied_ws = wb['fixed_applied']
        applied = _rows(applied_ws)
        if any(int(a.get('payment_id') or 0) == fid and a.get('year_month') == this_month for a in applied):
            return jsonify({'ok': False, 'error': 'already applied this month'}), 400
        applied_ws.append([fid, this_month])
        wb.save(DATA_PATH)
    return _add_txn({'amount': fp['amount'], 'description': fp['name'],
                     'account': fp['account'], 'category': fp['category'],
                     'date': today_str}, 'expense')

@app.route('/api/fixed/<int:fid>/undo', methods=['POST'])
def undo_fixed(fid):
    this_month = datetime.now().strftime('%Y-%m')
    with XLSX_LOCK:
        wb = _load_wb()
        fixed = _rows(wb['fixed_payments'])
        fp = next((row for row in fixed if int(row.get('id') or 0) == fid), None)
        if not fp:
            return jsonify({'ok': False}), 404

        txn_ws = wb['transactions']
        txns = _rows(txn_ws)
        matches = [
            t for t in txns
            if t.get('description') == fp.get('name')
            and t.get('account') == fp.get('account')
            and t.get('type') == 'expense'
            and str(t.get('date') or '').startswith(this_month)
        ]
        t = max(matches, key=lambda row: int(row.get('id') or 0), default=None)
        if t:
            for row_idx in range(txn_ws.max_row, 1, -1):
                if int(txn_ws.cell(row=row_idx, column=1).value or 0) == int(t.get('id') or 0):
                    txn_ws.delete_rows(row_idx, 1)
                    break

        applied_ws = wb['fixed_applied']
        for row_idx in range(applied_ws.max_row, 1, -1):
            if int(applied_ws.cell(row=row_idx, column=1).value or 0) == fid and applied_ws.cell(row=row_idx, column=2).value == this_month:
                applied_ws.delete_rows(row_idx, 1)
        wb.save(DATA_PATH)

    if t:
        acc = t.get('account')
        bal = get_balances()[acc]
        set_balance(acc, round_acc(acc, bal + float(t.get('amount') or 0)))
    return jsonify({'ok': True})

# Modern account model overrides
CURRENCIES['krw']['symbol'] = '\u20a9'
for _currency_key, _currency_meta in CURRENCIES.items():
    _currency_meta.setdefault('code', _currency_key.upper())

def round_currency(currency, val):
    currency = str(currency or '').strip().lower()
    if currency not in CURRENCIES:
        raise ValueError('unknown currency')
    return round(float(val), CURRENCIES[currency]['decimals'])

def init_data():
    if os.path.exists(DATA_PATH):
        wb = load_workbook(DATA_PATH)
    else:
        wb = Workbook()
        wb.remove(wb.active)

    for sheet, headers in SHEETS.items():
        ws = wb[sheet] if sheet in wb.sheetnames else wb.create_sheet(sheet)
        _ensure_headers(ws, headers)

    config = wb['config']
    existing = {r.get('key') for r in _rows(config)}
    for currency in CURRENCIES:
        key = f'balance_{currency}'
        if key not in existing:
            config.append([key, 0])

    accounts_ws = wb['accounts']
    if not _rows(accounts_ws):
        legacy_balances = {
            str(r.get('key') or '').replace('balance_', ''): float(r.get('value') or 0)
            for r in _rows(config)
            if str(r.get('key') or '').startswith('balance_')
        }
        for currency in CURRENCIES:
            accounts_ws.append([
                currency,
                LEGACY_ACCOUNT_BANKS[currency],
                currency,
                round_currency(currency, legacy_balances.get(currency, 0)),
                datetime.now().isoformat(timespec='seconds'),
                False,
                DEFAULT_ACC_COLORS.get(currency, '#4a90f8'),
            ])

    wb.save(DATA_PATH)

def _is_archived(value):
    return str(value).lower() in ('1', 'true', 'yes')

def _currency_id(value):
    currency = str(value or '').strip().lower()
    if currency not in CURRENCIES:
        raise ValueError('unknown currency')
    return currency

DEFAULT_ACC_COLORS = {'uyu': '#4a90f8', 'usd': '#32d74b', 'krw': '#bf5af2'}

def _account_json(row):
    currency = _currency_id(row.get('currency') or 'uyu')
    color = str(row.get('color') or '').strip() or DEFAULT_ACC_COLORS.get(currency, '#4a90f8')
    return {
        'id': str(row.get('id') or ''),
        'bank': str(row.get('bank') or '').strip() or 'Account',
        'currency': currency,
        'balance': round_currency(currency, row.get('balance') or 0),
        'created_at': row.get('created_at') or '',
        'archived': _is_archived(row.get('archived')),
        'currency_meta': CURRENCIES[currency],
        'color': color,
    }

def _accounts_from_wb(wb, include_archived=False):
    accounts = []
    for row in _rows(wb['accounts']):
        try:
            account = _account_json(row)
        except ValueError:
            continue
        if account['id'] and (include_archived or not account['archived']):
            accounts.append(account)
    return accounts

def get_accounts(include_archived=False):
    with XLSX_LOCK:
        wb = _load_wb()
        return _accounts_from_wb(wb, include_archived)

def get_accounts_map(include_archived=False):
    return {account['id']: account for account in get_accounts(include_archived)}

def get_account(account_id):
    return get_accounts_map().get(str(account_id or ''))

def _default_account_id():
    accounts = get_accounts()
    if not accounts:
        raise ValueError('no accounts configured')
    return 'uyu' if any(account['id'] == 'uyu' for account in accounts) else accounts[0]['id']

def _new_account_id(existing_ids):
    while True:
        account_id = 'acct_' + secrets.token_hex(4)
        if account_id not in existing_ids:
            return account_id

def get_balances():
    return {account['id']: account['balance'] for account in get_accounts()}

def set_balance(account_id, val):
    account_id = str(account_id or '')
    with XLSX_LOCK:
        wb = _load_wb()
        ws = wb['accounts']
        headers = _headers(ws)
        id_col = headers.index('id') + 1
        currency_col = headers.index('currency') + 1
        balance_col = headers.index('balance') + 1
        rounded = None
        for row_idx in range(2, ws.max_row + 1):
            if str(ws.cell(row=row_idx, column=id_col).value or '') == account_id:
                currency = ws.cell(row=row_idx, column=currency_col).value
                rounded = round_currency(currency, val)
                ws.cell(row=row_idx, column=balance_col, value=rounded)
                break
        else:
            raise ValueError('unknown account')

        if account_id in CURRENCIES:
            key = f'balance_{account_id}'
            config = wb['config']
            for row_idx in range(2, config.max_row + 1):
                if config.cell(row=row_idx, column=1).value == key:
                    config.cell(row=row_idx, column=2, value=rounded)
                    break
            else:
                config.append([key, rounded])
        wb.save(DATA_PATH)
    return rounded

def round_acc(account_id, val):
    account = get_account(account_id)
    if not account:
        raise ValueError('unknown account')
    return round_currency(account['currency'], val)

def _blank_stats():
    return {'exp_cat': {}, 'monthly': {}, 'last30': 0.0, 'in30': 0.0, 'total_txns': 0}

def _annotate_txn(txn, accounts):
    account = accounts.get(str(txn.get('account') or ''))
    if not account:
        return None
    item = dict(txn)
    item['account_name'] = account['bank']
    item['currency'] = account['currency']
    return item

def build_summary():
    with XLSX_LOCK:
        wb = _load_wb()
        accounts = _accounts_from_wb(wb)
        all_accounts = _accounts_from_wb(wb, include_archived=True)
        txns = _rows(wb['transactions'])
        fixed = _rows(wb['fixed_payments'])
        applied = _rows(wb['fixed_applied'])

    accounts_map = {account['id']: account for account in accounts}
    txns.sort(key=lambda t: (str(t.get('date') or ''), int(t.get('id') or 0)), reverse=True)
    fixed.sort(key=lambda f: int(f.get('day') or 0))

    today_str = datetime.now().strftime('%Y-%m-%d')
    this_month = today_str[:7]
    today_day = int(today_str[8:])
    cutoff = (datetime.now() - timedelta(days=30)).strftime('%Y-%m-%d')
    applied_set = {(a['payment_id'], a['year_month']) for a in applied}

    stats = {account['id']: _blank_stats() for account in accounts}
    overview = {
        'by_currency': {
            currency: {'balance': 0.0, 'last30': 0.0, 'in30': 0.0, 'accounts': 0}
            for currency in CURRENCIES
        },
        'monthly': {},
        'exp_cat': {},
        'total_txns': len(txns),
    }

    for account in accounts:
        bucket = overview['by_currency'][account['currency']]
        bucket['balance'] += account['balance']
        bucket['accounts'] += 1

    recent = []
    for raw_txn in txns:
        txn = _annotate_txn(raw_txn, accounts_map)
        if not txn:
            continue
        recent.append(txn)
        account_id = txn['account']
        currency = txn['currency']
        amount = float(txn.get('amount') or 0)
        date_str = str(txn.get('date') or '')
        month = date_str[:7]
        stats[account_id]['total_txns'] += 1
        if txn.get('type') == 'expense':
            category = txn.get('category') or 'Others'
            stats[account_id]['exp_cat'][category] = stats[account_id]['exp_cat'].get(category, 0) + amount
            overview['exp_cat'][category] = overview['exp_cat'].get(category, 0) + amount
            if date_str >= cutoff:
                stats[account_id]['last30'] += amount
                overview['by_currency'][currency]['last30'] += amount
        elif date_str >= cutoff:
            stats[account_id]['in30'] += amount
            overview['by_currency'][currency]['in30'] += amount
        if month:
            stats[account_id]['monthly'].setdefault(month, {'in': 0.0, 'out': 0.0})
            overview['monthly'].setdefault(month, {})
            overview['monthly'][month].setdefault(currency, {'in': 0.0, 'out': 0.0})
            direction = 'in' if txn.get('type') == 'fund' else 'out'
            stats[account_id]['monthly'][month][direction] += amount
            overview['monthly'][month][currency][direction] += amount

    for account_id in stats:
        months = sorted(stats[account_id]['monthly'])[-6:]
        stats[account_id]['monthly'] = {month: stats[account_id]['monthly'][month] for month in months}
    months = sorted(overview['monthly'])[-6:]
    overview['monthly'] = {month: overview['monthly'][month] for month in months}

    normalized_fixed = []
    for fixed_payment in fixed:
        account = accounts_map.get(str(fixed_payment.get('account') or ''))
        if not account:
            continue
        item = dict(fixed_payment)
        item['account_name'] = account['bank']
        item['currency'] = account['currency']
        item['applied_this_month'] = (item['id'], this_month) in applied_set
        item['due_this_month'] = item['day'] <= today_day and not item['applied_this_month']
        normalized_fixed.append(item)

    return {
        'balances': get_balances(),
        'stats': stats,
        'overview': overview,
        'recent': recent[:15],
        'fixed': normalized_fixed,
        'due_count': sum(1 for item in normalized_fixed if item['due_this_month']),
        'categories': CATEGORIES,
        'accounts': accounts_map,
        'account_list': accounts,
        'all_account_list': all_accounts,
        'currencies': CURRENCIES,
        'total_txns': len(txns),
    }


def modern_accounts():
    include_archived = str(request.args.get('include_archived') or '').lower() in ('1', 'true', 'yes')
    return jsonify(get_accounts(include_archived=include_archived))

def modern_create_account():
    data = request.json or {}
    bank = str(data.get('bank') or '').strip()
    if not bank:
        return jsonify({'ok': False, 'error': 'bank is required'}), 400
    try:
        currency = _currency_id(data.get('currency'))
        balance = round_currency(currency, data.get('balance') or 0)
    except (TypeError, ValueError):
        return jsonify({'ok': False, 'error': 'invalid currency or balance'}), 400

    color = str(data.get('color') or '').strip() or DEFAULT_ACC_COLORS.get(currency, '#4a90f8')

    with XLSX_LOCK:
        wb = _load_wb()
        existing_ids = {account['id'] for account in _accounts_from_wb(wb, include_archived=True)}
        account_id = _new_account_id(existing_ids)
        wb['accounts'].append([
            account_id,
            bank,
            currency,
            balance,
            datetime.now().isoformat(timespec='seconds'),
            False,
            color,
        ])
        wb.save(DATA_PATH)

    return jsonify({'ok': True, 'account': get_account(account_id)})


def modern_edit_account(account_id):
    account_id = str(account_id or '')
    data = request.json or {}
    bank = str(data.get('bank') or '').strip()
    if not bank:
        return jsonify({'ok': False, 'error': 'bank name is required'}), 400

    with XLSX_LOCK:
        wb = _load_wb()
        ws = wb['accounts']
        headers = _headers(ws)
        id_col = headers.index('id') + 1
        found = None
        for row_idx in range(2, ws.max_row + 1):
            if str(ws.cell(row=row_idx, column=id_col).value or '') == account_id:
                found = row_idx
                break
        if found is None:
            return jsonify({'ok': False, 'error': 'account not found'}), 404

        ws.cell(row=found, column=headers.index('bank') + 1, value=bank)

        new_currency = data.get('currency')
        if new_currency is not None:
            try:
                currency = _currency_id(new_currency)
                ws.cell(row=found, column=headers.index('currency') + 1, value=currency)
            except (ValueError, KeyError):
                return jsonify({'ok': False, 'error': 'invalid currency'}), 400
        else:
            currency = str(ws.cell(row=found, column=headers.index('currency') + 1).value or 'uyu')

        if data.get('balance') is not None:
            try:
                rounded = round_currency(currency, data['balance'])
                ws.cell(row=found, column=headers.index('balance') + 1, value=rounded)
            except (ValueError, TypeError):
                return jsonify({'ok': False, 'error': 'invalid balance'}), 400

        new_color = str(data.get('color') or '').strip()
        if new_color and 'color' in headers:
            ws.cell(row=found, column=headers.index('color') + 1, value=new_color)

        wb.save(DATA_PATH)
    return jsonify({'ok': True, 'account': get_account(account_id)})


def modern_delete_account(account_id):
    account_id = str(account_id or '')
    with XLSX_LOCK:
        wb = _load_wb()
        ws = wb['accounts']
        headers = _headers(ws)
        id_col = headers.index('id') + 1
        archived_col = headers.index('archived') + 1
        found = None
        for row_idx in range(2, ws.max_row + 1):
            if str(ws.cell(row=row_idx, column=id_col).value or '') == account_id:
                found = row_idx
                break
        if found is None:
            return jsonify({'ok': False, 'error': 'account not found'}), 404
        if _is_archived(ws.cell(row=found, column=archived_col).value):
            return jsonify({'ok': True})

        ws.cell(row=found, column=archived_col, value=True)
        wb.save(DATA_PATH)

    return jsonify({'ok': True})

def modern_permanent_delete_account(account_id):
    account_id = str(account_id or '')
    with XLSX_LOCK:
        wb = _load_wb()
        accounts_ws = wb['accounts']
        headers = _headers(accounts_ws)
        id_col = headers.index('id') + 1
        archived_col = headers.index('archived') + 1
        found = None
        for row_idx in range(2, accounts_ws.max_row + 1):
            if str(accounts_ws.cell(row=row_idx, column=id_col).value or '') == account_id:
                found = row_idx
                break
        if found is None:
            return jsonify({'ok': False, 'error': 'account not found'}), 404
        if not _is_archived(accounts_ws.cell(row=found, column=archived_col).value):
            return jsonify({'ok': False, 'error': 'deactivate account before deleting it'}), 400

        fixed_ids = set()
        fixed_ws = wb['fixed_payments']
        fixed_headers = _headers(fixed_ws)
        fixed_id_col = fixed_headers.index('id') + 1
        fixed_account_col = fixed_headers.index('account') + 1
        for row_idx in range(fixed_ws.max_row, 1, -1):
            if str(fixed_ws.cell(row=row_idx, column=fixed_account_col).value or '') == account_id:
                fixed_ids.add(int(fixed_ws.cell(row=row_idx, column=fixed_id_col).value or 0))
                fixed_ws.delete_rows(row_idx, 1)

        applied_ws = wb['fixed_applied']
        for row_idx in range(applied_ws.max_row, 1, -1):
            if int(applied_ws.cell(row=row_idx, column=1).value or 0) in fixed_ids:
                applied_ws.delete_rows(row_idx, 1)

        txn_ws = wb['transactions']
        txn_headers = _headers(txn_ws)
        txn_account_col = txn_headers.index('account') + 1
        for row_idx in range(txn_ws.max_row, 1, -1):
            if str(txn_ws.cell(row=row_idx, column=txn_account_col).value or '') == account_id:
                txn_ws.delete_rows(row_idx, 1)

        if account_id in CURRENCIES:
            config_ws = wb['config']
            key = f'balance_{account_id}'
            for row_idx in range(config_ws.max_row, 1, -1):
                if config_ws.cell(row=row_idx, column=1).value == key:
                    config_ws.delete_rows(row_idx, 1)

        accounts_ws.delete_rows(found, 1)
        wb.save(DATA_PATH)

    return jsonify({'ok': True})

def _add_txn(data, txn_type):
    data = data or {}
    account_id = str(data.get('account') or _default_account_id())
    if not get_account(account_id):
        return jsonify({'ok': False, 'error': 'unknown account'}), 400
    try:
        amount = round_acc(account_id, abs(float(data.get('amount') or 0)))
    except ValueError:
        return jsonify({'ok': False, 'error': 'invalid amount'}), 400
    if amount <= 0:
        return jsonify({'ok': False, 'error': 'amount must be greater than zero'}), 400

    balances = get_balances()
    new_balance = balances[account_id] + amount if txn_type == 'fund' else balances[account_id] - amount
    new_balance = set_balance(account_id, new_balance)
    with XLSX_LOCK:
        wb = _load_wb()
        ws = wb['transactions']
        ws.append([
            _next_id(ws),
            data.get('date', datetime.now().strftime('%Y-%m-%d')),
            data.get('description', ''),
            amount,
            data.get('category', 'Others'),
            txn_type,
            account_id,
        ])
        wb.save(DATA_PATH)
    return jsonify({'ok': True, 'balance': new_balance})

def modern_delete_txn(tid):
    with XLSX_LOCK:
        wb = _load_wb()
        ws = wb['transactions']
        found = None
        for row_idx in range(2, ws.max_row + 1):
            if int(ws.cell(row=row_idx, column=1).value or 0) == tid:
                found = row_idx
                break
        if found is None:
            return jsonify({'ok': False}), 404
        headers = _headers(ws)
        txn = {headers[col - 1]: ws.cell(row=found, column=col).value for col in range(1, len(headers) + 1)}
        ws.delete_rows(found, 1)
        wb.save(DATA_PATH)

    account_id = str(txn.get('account') or '')
    if not get_account(account_id):
        return jsonify({'ok': False, 'error': 'unknown account'}), 400
    amount = float(txn.get('amount') or 0)
    balance = get_balances()[account_id]
    new_balance = balance - amount if txn.get('type') == 'fund' else balance + amount
    set_balance(account_id, new_balance)
    return jsonify({'ok': True})

def modern_edit_txn(tid):
    data = request.json or {}
    with XLSX_LOCK:
        wb = _load_wb()
        ws = wb['transactions']
        headers = _headers(ws)
        col = {h: i + 1 for i, h in enumerate(headers)}
        found = None
        for row_idx in range(2, ws.max_row + 1):
            if int(ws.cell(row=row_idx, column=col['id']).value or 0) == tid:
                found = row_idx
                break
        if found is None:
            return jsonify({'ok': False, 'error': 'not found'}), 404
        old = {h: ws.cell(row=found, column=col[h]).value for h in headers}

    old_account = str(old.get('account') or '')
    old_amount = float(old.get('amount') or 0)
    old_type = old.get('type')

    new_account = str(data.get('account') or old_account)
    if not get_account(new_account):
        return jsonify({'ok': False, 'error': 'unknown account'}), 400
    try:
        new_amount = round_acc(new_account, abs(float(data.get('amount') or 0)))
    except (TypeError, ValueError):
        return jsonify({'ok': False, 'error': 'invalid amount'}), 400
    if new_amount <= 0:
        return jsonify({'ok': False, 'error': 'amount must be greater than zero'}), 400

    if get_account(old_account):
        bal = get_balances()[old_account]
        bal = bal - old_amount if old_type == 'fund' else bal + old_amount
        set_balance(old_account, bal)

    bal = get_balances()[new_account]
    bal = bal + new_amount if old_type == 'fund' else bal - new_amount
    set_balance(new_account, bal)

    with XLSX_LOCK:
        wb = _load_wb()
        ws = wb['transactions']
        headers = _headers(ws)
        col = {h: i + 1 for i, h in enumerate(headers)}
        for row_idx in range(2, ws.max_row + 1):
            if int(ws.cell(row=row_idx, column=col['id']).value or 0) == tid:
                ws.cell(row=row_idx, column=col['date']).value = data.get('date') or old.get('date')
                ws.cell(row=row_idx, column=col['description']).value = data.get('description', '')
                ws.cell(row=row_idx, column=col['amount']).value = new_amount
                ws.cell(row=row_idx, column=col['category']).value = data.get('category', 'Others')
                ws.cell(row=row_idx, column=col['account']).value = new_account
                break
        wb.save(DATA_PATH)
    return jsonify({'ok': True})

def modern_set_balance():
    data = request.json or {}
    account_id = str(data.get('account') or '')
    if not get_account(account_id):
        return jsonify({'ok': False, 'error': 'unknown account'}), 400
    balance = set_balance(account_id, float(data.get('balance') or 0))
    return jsonify({'ok': True, 'balance': balance})

def modern_fixed():
    with XLSX_LOCK:
        wb = _load_wb()
        rows = _rows(wb['fixed_payments'])
        applied = _rows(wb['fixed_applied'])
    accounts = get_accounts_map()
    this_month = datetime.now().strftime('%Y-%m')
    today_day = datetime.now().day
    applied_set = {(a['payment_id'], a['year_month']) for a in applied}
    result = []
    for row in sorted(rows, key=lambda item: int(item.get('day') or 0)):
        account = accounts.get(str(row.get('account') or ''))
        if not account:
            continue
        item = dict(row)
        item['account_name'] = account['bank']
        item['currency'] = account['currency']
        item['applied_this_month'] = (item['id'], this_month) in applied_set
        item['due_this_month'] = item['day'] <= today_day and not item['applied_this_month']
        result.append(item)
    return jsonify(result)

def modern_create_fixed():
    data = request.json or {}
    account_id = str(data.get('account') or _default_account_id())
    if not get_account(account_id):
        return jsonify({'ok': False, 'error': 'unknown account'}), 400
    day = int(data.get('day', 1))
    if not 1 <= day <= 31:
        return jsonify({'ok': False, 'error': 'day must be 1-31'}), 400
    amount = round_acc(account_id, float(data.get('amount', 0)))
    with XLSX_LOCK:
        wb = _load_wb()
        ws = wb['fixed_payments']
        fixed_id = _next_id(ws)
        ws.append([fixed_id, data.get('name', ''), amount, account_id, data.get('category', 'Others'), day])
        wb.save(DATA_PATH)
    return jsonify({'ok': True, 'id': fixed_id})

def modern_edit_fixed(fid):
    data = request.json or {}
    account_id = str(data.get('account') or _default_account_id())
    if not get_account(account_id):
        return jsonify({'ok': False, 'error': 'unknown account'}), 400
    try:
        day = int(data.get('day', 1))
    except (TypeError, ValueError):
        return jsonify({'ok': False, 'error': 'invalid day'}), 400
    if not 1 <= day <= 31:
        return jsonify({'ok': False, 'error': 'day must be 1-31'}), 400
    try:
        amount = round_acc(account_id, float(data.get('amount', 0)))
    except (TypeError, ValueError):
        return jsonify({'ok': False, 'error': 'invalid amount'}), 400
    with XLSX_LOCK:
        wb = _load_wb()
        ws = wb['fixed_payments']
        headers = _headers(ws)
        col = {h: headers.index(h) + 1 for h in headers}
        found = False
        for row_idx in range(2, ws.max_row + 1):
            if int(ws.cell(row=row_idx, column=col['id']).value or 0) == fid:
                ws.cell(row=row_idx, column=col['name']).value = data.get('name', '')
                ws.cell(row=row_idx, column=col['amount']).value = amount
                ws.cell(row=row_idx, column=col['account']).value = account_id
                ws.cell(row=row_idx, column=col['category']).value = data.get('category', 'Others')
                ws.cell(row=row_idx, column=col['day']).value = day
                found = True
                break
        if not found:
            return jsonify({'ok': False, 'error': 'not found'}), 404
        wb.save(DATA_PATH)
    return jsonify({'ok': True})

def modern_export():
    with XLSX_LOCK:
        wb = _load_wb()
        config = {r.get('key'): r.get('value') for r in _rows(wb['config'])}
        accounts = _accounts_from_wb(wb, include_archived=True)
        txns = _rows(wb['transactions'])
        fixed = _rows(wb['fixed_payments'])
        applied_rows = _rows(wb['fixed_applied'])

    applied = {}
    for row in applied_rows:
        month = row.get('year_month')
        payment_id = row.get('payment_id')
        if month:
            applied.setdefault(month, []).append(payment_id)

    return jsonify({
        'version': 2,
        'exported_at': datetime.now().isoformat(),
        'accounts': accounts,
        'config': config,
        'txns': txns,
        'fixed': fixed,
        'applied': applied,
    })

def modern_import():
    data = request.json or {}
    accounts = data.get('accounts') if isinstance(data.get('accounts'), list) else []
    config = data.get('config') or {}
    txns = data.get('txns') if isinstance(data.get('txns'), list) else []
    fixed = data.get('fixed') if isinstance(data.get('fixed'), list) else []
    applied = data.get('applied') if isinstance(data.get('applied'), dict) else {}

    with XLSX_LOCK:
        wb = _load_wb()
        for sheet, headers in SHEETS.items():
            ws = wb[sheet]
            if ws.max_row > 1:
                ws.delete_rows(2, ws.max_row - 1)
            _ensure_headers(ws, headers)

        imported_ids = []
        if accounts:
            seen = set()
            for row in accounts:
                try:
                    account_id = str(row.get('id') or '').strip()
                    currency = _currency_id(row.get('currency'))
                    if not account_id or account_id in seen:
                        continue
                    balance = round_currency(currency, row.get('balance') or 0)
                except (TypeError, ValueError):
                    continue
                seen.add(account_id)
                imported_ids.append(account_id)
                color = str(row.get('color') or '').strip() or DEFAULT_ACC_COLORS.get(currency, '#4a90f8')
                wb['accounts'].append([
                    account_id,
                    str(row.get('bank') or '').strip() or 'Account',
                    currency,
                    balance,
                    row.get('created_at') or datetime.now().isoformat(timespec='seconds'),
                    _is_archived(row.get('archived')),
                    color,
                ])
        else:
            for currency in CURRENCIES:
                imported_ids.append(currency)
                wb['accounts'].append([
                    currency,
                    LEGACY_ACCOUNT_BANKS[currency],
                    currency,
                    round_currency(currency, config.get(f'balance_{currency}') or 0),
                    datetime.now().isoformat(timespec='seconds'),
                    False,
                    DEFAULT_ACC_COLORS.get(currency, '#4a90f8'),
                ])

        if not imported_ids:
            return jsonify({'ok': False, 'error': 'no valid accounts'}), 400

        accounts_map = {row['id']: row for row in _accounts_from_wb(wb, include_archived=True)}
        for currency in CURRENCIES:
            account = accounts_map.get(currency)
            wb['config'].append([f'balance_{currency}', account['balance'] if account else 0])

        txns_ws = wb['transactions']
        for row in txns:
            account_id = str(row.get('account') or '')
            if account_id not in imported_ids:
                account_id = imported_ids[0]
            txn_type = row.get('type') if row.get('type') in ('fund', 'expense') else 'expense'
            txns_ws.append([
                int(row.get('id') or _next_id(txns_ws)),
                row.get('date') or datetime.now().strftime('%Y-%m-%d'),
                row.get('description') or '',
                round_currency(accounts_map[account_id]['currency'], float(row.get('amount') or 0)),
                row.get('category') or 'Others',
                txn_type,
                account_id,
            ])

        fixed_ws = wb['fixed_payments']
        for row in fixed:
            account_id = str(row.get('account') or '')
            if account_id not in imported_ids:
                account_id = imported_ids[0]
            fixed_ws.append([
                int(row.get('id') or _next_id(fixed_ws)),
                row.get('name') or '',
                round_currency(accounts_map[account_id]['currency'], float(row.get('amount') or 0)),
                account_id,
                row.get('category') or 'Others',
                min(31, max(1, int(row.get('day') or 1))),
            ])

        applied_ws = wb['fixed_applied']
        for month, ids in applied.items():
            if isinstance(ids, list):
                for payment_id in ids:
                    applied_ws.append([int(payment_id), month])

        wb.save(DATA_PATH)

    return jsonify({'ok': True})

def modern_clear():
    with XLSX_LOCK:
        wb = _load_wb()
        for sheet, headers in SHEETS.items():
            ws = wb[sheet]
            if ws.max_row > 1:
                ws.delete_rows(2, ws.max_row - 1)
            _ensure_headers(ws, headers)

        config = wb['config']
        for currency in CURRENCIES:
            config.append([f'balance_{currency}', 0])

        accounts_ws = wb['accounts']
        for currency in CURRENCIES:
            accounts_ws.append([
                currency,
                LEGACY_ACCOUNT_BANKS[currency],
                currency,
                0,
                datetime.now().isoformat(timespec='seconds'),
                False,
                DEFAULT_ACC_COLORS.get(currency, '#4a90f8'),
            ])

        wb.save(DATA_PATH)
    return jsonify({'ok': True})

app.add_url_rule('/api/transactions/<int:tid>', 'delete_txn', modern_delete_txn, methods=['DELETE'])
app.add_url_rule('/api/transactions/<int:tid>', 'edit_txn', modern_edit_txn, methods=['PUT'])
app.add_url_rule('/api/balance', 'api_set_balance', modern_set_balance, methods=['POST'])
app.add_url_rule('/api/export', 'api_export', modern_export, methods=['GET'])
app.add_url_rule('/api/import', 'api_import', modern_import, methods=['POST'])
app.add_url_rule('/api/clear', 'api_clear', modern_clear, methods=['POST'])
app.add_url_rule('/api/fixed', 'api_fixed', modern_fixed, methods=['GET'])
app.add_url_rule('/api/fixed', 'create_fixed', modern_create_fixed, methods=['POST'])
app.add_url_rule('/api/fixed/<int:fid>', 'edit_fixed', modern_edit_fixed, methods=['PUT'])
app.add_url_rule('/api/accounts', 'modern_accounts', modern_accounts, methods=['GET'])
app.add_url_rule('/api/accounts', 'modern_create_account', modern_create_account, methods=['POST'])
app.add_url_rule('/api/accounts/<account_id>', 'modern_edit_account', modern_edit_account, methods=['PUT'])
app.add_url_rule('/api/accounts/<account_id>', 'modern_delete_account', modern_delete_account, methods=['DELETE'])
app.add_url_rule('/api/accounts/<account_id>/permanent', 'modern_permanent_delete_account', modern_permanent_delete_account, methods=['DELETE'])


def _parse_semver(tag):
    s = (tag or '').lstrip('vV').strip()
    parts = s.split('-', 1)[0].split('.')
    out = []
    for p in parts:
        try:
            out.append(int(p))
        except ValueError:
            out.append(0)
    while len(out) < 3:
        out.append(0)
    return tuple(out[:3])


@app.route('/api/version/check')
def api_version_check():
    url = f'https://api.github.com/repos/{GITHUB_REPO}/releases/latest'
    req = urllib.request.Request(url, headers={
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'ClariFi-Updater',
    })
    try:
        with urllib.request.urlopen(req, timeout=6) as resp:
            data = json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return jsonify({
                'ok': True, 'current': APP_VERSION, 'latest': None,
                'update_available': False, 'message': 'No releases published yet',
            })
        return jsonify({'ok': False, 'current': APP_VERSION, 'error': f'github error {e.code}'}), 502
    except (urllib.error.URLError, TimeoutError, OSError):
        return jsonify({'ok': False, 'current': APP_VERSION, 'error': 'could not reach update server'}), 502
    except (ValueError, KeyError):
        return jsonify({'ok': False, 'current': APP_VERSION, 'error': 'invalid response from update server'}), 502

    latest_tag = data.get('tag_name') or ''
    html_url = data.get('html_url') or f'https://github.com/{GITHUB_REPO}/releases'
    published = data.get('published_at')
    notes = data.get('body') or ''

    assets = data.get('assets') or []
    installer = None
    for a in assets:
        name = (a.get('name') or '').lower()
        if name.endswith('.exe') or name.endswith('.msi'):
            installer = a.get('browser_download_url')
            break

    update_available = _parse_semver(latest_tag) > _parse_semver(APP_VERSION)

    return jsonify({
        'ok': True,
        'current': APP_VERSION,
        'latest': latest_tag,
        'update_available': update_available,
        'release_url': html_url,
        'installer_url': installer,
        'published_at': published,
        'notes': notes,
        'repo': GITHUB_REPO,
    })

@app.route('/api/version/download', methods=['POST'])
def api_version_download():
    data = request.json or {}
    url = data.get('url')
    if not url or not isinstance(url, str) or not url.startswith('https://'):
        return jsonify({'ok': False, 'error': 'invalid installer url'}), 400
    if not (url.endswith('.exe') or url.endswith('.msi')):
        return jsonify({'ok': False, 'error': 'unexpected installer extension'}), 400

    import tempfile
    dest = os.path.join(tempfile.gettempdir(), os.path.basename(url))
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'ClariFi-Updater'})
        with urllib.request.urlopen(req, timeout=120) as resp, open(dest, 'wb') as f:
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                f.write(chunk)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return jsonify({'ok': False, 'error': f'download failed: {e}'}), 502
    return jsonify({'ok': True, 'path': dest})


@app.route('/api/version/install', methods=['POST'])
def api_version_install():
    data = request.json or {}
    path = data.get('path') or ''
    if not path or not os.path.isfile(path):
        return jsonify({'ok': False, 'error': 'installer file not found'}), 400
    if not (path.lower().endswith('.exe') or path.lower().endswith('.msi')):
        return jsonify({'ok': False, 'error': 'unexpected installer extension'}), 400

    import subprocess, threading
    flags = 0
    if os.name == 'nt':
        flags = getattr(subprocess, 'DETACHED_PROCESS', 0) | getattr(subprocess, 'CREATE_NEW_PROCESS_GROUP', 0)
    try:
        subprocess.Popen(
            [path, '/SILENT', '/SUPPRESSMSGBOXES'],
            creationflags=flags,
            close_fds=True,
        )
    except OSError as e:
        return jsonify({'ok': False, 'error': f'could not launch installer: {e}'}), 500

    # Give the response time to flush, then kill ourselves so the installer
    # can replace our files. The installer's [Run] entry relaunches the app.
    threading.Timer(1.2, lambda: os._exit(0)).start()
    return jsonify({'ok': True})


if __name__ == '__main__':
    init_data()
    port = int(os.environ.get('PORT', 5000))
    print(
        '\n'
        f'  ClariFi Desktop -> http://localhost:{port}\n'
    )
    app.run(host='127.0.0.1', port=port, debug=False)
