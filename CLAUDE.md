# ClariFi — Project Expert Guide

## Stack & Versions

| Component | Version | Notes |
|-----------|---------|-------|
| Python | 3.13.13 | cpython, Windows |
| Flask | 3.1.3 | `app.secret_key` regenerated on each restart unless `SECRET_KEY` env var set |
| openpyxl | 3.1.5 | Only dependency besides Flask; no ORM, no SQLite |
| Werkzeug | (Flask dep) | Dev server only — `debug=False`, binds to `127.0.0.1` only |
| Frontend | Vanilla JS | No npm, no bundler, no Chart.js — canvas charts are **fully handwritten** |

No `requirements.txt` exists. Installation is just `pip install flask openpyxl`.

---

## Folder Structure

```
basic-personal-finances-tracker/
├── app.py                   ← Entire backend: config, models, all 20 routes (836 lines)
├── templates/
│   └── index.html           ← Entire frontend: CSS, HTML, JS (1735 lines)
├── finance_data.xlsx        ← Runtime database, auto-created, gitignored
├── Start.bat                ← Kills port 5000, then runs python app.py
└── CLAUDE.md
```

No blueprints, no separate routes file, no models file, no services layer — everything lives in `app.py`.

---

## Excel "Database" — How It Actually Works

The database is `finance_data.xlsx`. It is **not SQLite**, not a real DB. It is read from disk on every request, mutated in memory, and saved back.

### The 5 sheets

```python
SHEETS = {
    'config':         ['key', 'value'],
    'accounts':       ['id', 'bank', 'currency', 'balance', 'created_at', 'archived', 'color'],
    'transactions':   ['id', 'date', 'description', 'amount', 'category', 'type', 'account'],
    'fixed_payments': ['id', 'name', 'amount', 'account', 'category', 'day'],
    'fixed_applied':  ['payment_id', 'year_month'],
}
```

Row 1 of every sheet = headers. Data starts at row 2.

### The 4 worksheet utility functions (used everywhere)

```python
_headers(ws)              # → ['id', 'bank', ...] from row 1
_ensure_headers(ws, cols) # idempotent header writer used in init_data()
_rows(ws)                 # → list[dict], skips fully-empty rows, header-keyed
_next_id(ws)              # scans all 'id' values, returns max+1 (integer)
```

### The canonical read/write pattern

Every route that touches the file uses this exact structure:

```python
with XLSX_LOCK:
    wb = _load_wb()          # load_workbook(DATA_PATH) — fresh every call
    ws = wb['sheet_name']
    # ... read or mutate ws ...
    wb.save(DATA_PATH)       # must be inside the lock
```

`XLSX_LOCK` is a `threading.Lock()` — it is **not reentrant**. Never acquire it from a call stack that already holds it (causes deadlock). `set_balance()` acquires `XLSX_LOCK` internally — do not call it from inside an existing `with XLSX_LOCK` block.

### Deleting rows

Always iterate **backwards** to avoid index shifts:

```python
for row_idx in range(ws.max_row, 1, -1):
    if condition:
        ws.delete_rows(row_idx, 1)
```

---

## Domain Models & Business Rules

### Account

- **ID format**: Legacy accounts (created at init) use the currency code as ID — string `'krw'`, `'uyu'`, `'usd'`. New accounts use `'acct_' + secrets.token_hex(4)` (e.g., `'acct_3f4a8b2c'`).
- **Currency**: stored lowercase (`'krw'`, `'uyu'`, `'usd'`). Always normalize with `_currency_id(val)` before storing.
- **Archived = soft delete**: `archived=True` hides the account from the UI but preserves all data. Only archived accounts can be permanently deleted.
- **Permanent delete cascades**: removes all fixed_payments, all fixed_applied records, all transactions, and the balance_* config row (for legacy accounts).
- **Balance source**: always read from `accounts` sheet. The `config` sheet balance_* keys are legacy — `set_balance()` keeps them in sync for legacy account IDs, but `get_balances()` reads accounts, not config.

### Transaction

- **type**: only two valid string values: `'fund'` (income) and `'expense'`. Never `'income'`, never `'debit'`.
- **amount**: stored as positive float regardless of type. The sign is implied by `type`.
- **Deletion reverses balance**: deleting a `fund` subtracts; deleting an `expense` adds back. This is done in `modern_delete_txn()`.
- **account field**: stores the account ID string. Transactions for archived accounts are kept, but orphaned (no active account) transactions are skipped by `_annotate_txn()`.
- **Balance adjustment (POST /api/balance) does NOT create a transaction** — it directly updates the balance. This is intentional.

### Fixed Payment

- **ID**: integer, auto-incremented via `_next_id()`.
- **day**: integer 1–31, represents the day of month it's due.
- **Applied tracking**: `fixed_applied` sheet stores `(payment_id, year_month)` pairs. A payment is "applied" when that pair exists for the current month.
- **Due**: `day <= today_day AND NOT applied this month`.
- **Applying** creates an `expense` transaction and appends to `fixed_applied` — these are two separate writes, the applied record first, the transaction via `_add_txn`.
- **Undo matching**: finds the most recent expense transaction matching the fixed payment's name, account, and current month — not by transaction ID. This means if you manually add an expense with the same name/account, undo could accidentally delete it.

### Currency

```python
CURRENCIES = {
    'krw': {'code': 'KRW', 'name': 'Korean Won',    'symbol': '₩',   'decimals': 0},
    'uyu': {'code': 'UYU', 'name': 'Uruguayan Peso', 'symbol': '$U',  'decimals': 2},
    'usd': {'code': 'USD', 'name': 'US Dollar',      'symbol': 'US$', 'decimals': 2},
}
```

- Keys are **always lowercase**. Passing uppercase to `round_currency()` raises `ValueError`.
- `round_currency(currency, val)` — canonical rounding, takes lowercase string.
- `round_acc(account_id, val)` — shortcut that looks up the account's currency first.
- KRW rounds to 0 decimals; UYU and USD round to 2.

---

## Route Registration — Two Styles

Early routes use `@app.route` decorator:

```python
@app.route('/api/summary')
def api_summary(): return jsonify(build_summary())
```

Later `modern_*` functions (added during multi-account refactor) use `app.add_url_rule()` at the bottom of the file (lines 816–826):

```python
app.add_url_rule('/api/accounts', 'modern_accounts', modern_accounts, methods=['GET'])
```

Both styles coexist. New routes should follow the `add_url_rule` pattern to stay consistent with recent additions.

### Full Route Map

| Method | Path | Handler |
|--------|------|---------|
| GET | `/` | `index` |
| GET | `/favicon.ico` | `favicon` |
| GET | `/api/summary` | `api_summary` |
| GET | `/api/transactions` | `api_transactions` |
| POST | `/api/fund` | `add_fund` |
| POST | `/api/expense` | `add_expense` |
| DELETE | `/api/fixed/<int:fid>` | `delete_fixed` |
| POST | `/api/fixed/<int:fid>/apply` | `apply_fixed` |
| POST | `/api/fixed/<int:fid>/undo` | `undo_fixed` |
| DELETE | `/api/transactions/<int:tid>` | `modern_delete_txn` |
| POST | `/api/balance` | `modern_set_balance` |
| GET | `/api/export` | `modern_export` |
| POST | `/api/import` | `modern_import` |
| GET | `/api/fixed` | `modern_fixed` |
| POST | `/api/fixed` | `modern_create_fixed` |
| GET | `/api/accounts` | `modern_accounts` |
| POST | `/api/accounts` | `modern_create_account` |
| PUT | `/api/accounts/<account_id>` | `modern_edit_account` |
| DELETE | `/api/accounts/<account_id>` | `modern_delete_account` |
| DELETE | `/api/accounts/<account_id>/permanent` | `modern_permanent_delete_account` |

---

## Error Handling Pattern

**All API responses follow this exact shape:**

```python
# Success
return jsonify({'ok': True, ...})

# Error
return jsonify({'ok': False, 'error': 'descriptive message'}), HTTP_STATUS
```

- `400` — bad input (invalid amount, unknown currency, missing required field)
- `404` — resource not found (account ID, transaction ID, fixed payment ID)
- No 500 handlers, no global `@app.errorhandler`, no logging.

**Input parsing pattern:**

```python
try:
    currency = _currency_id(data.get('currency'))
    balance = round_currency(currency, data.get('balance') or 0)
except (TypeError, ValueError):
    return jsonify({'ok': False, 'error': 'invalid currency or balance'}), 400
```

Always catch `(TypeError, ValueError)` together — openpyxl can return `None` for missing cells, which causes `TypeError` on float conversion.

---

## Naming Conventions

| Thing | Convention | Example |
|-------|-----------|---------|
| Route handler functions | snake_case, newer ones prefixed `modern_` | `modern_create_account` |
| Private helpers | leading underscore | `_rows`, `_load_wb`, `_account_json` |
| Global constants | UPPER_SNAKE_CASE | `XLSX_LOCK`, `CATEGORIES`, `CURRENCIES` |
| Account IDs | lowercase string | `'uyu'`, `'acct_3f4a8b2c'` |
| Currency codes (internal) | lowercase string | `'krw'`, `'usd'` |
| Transaction types | lowercase literal | `'fund'`, `'expense'` |
| Year-month format | `'YYYY-MM'` | `'2025-11'` |
| Date format | `'YYYY-MM-DD'` | `'2025-11-15'` |
| JS functions | camelCase | `renderDash`, `submitExpense`, `loadAll` |
| JS API wrappers | `post(url, body)`, `del(url)` | two helpers only |

---

## Frontend Architecture

Single `templates/index.html` file — no build step, no npm, no bundler.

**Design tokens (CSS variables on `:root` and `[data-theme='light']`):**
- Dark: `#070710` background, `#4a90f8` accent
- Light: `#f0f2f8` background, `#007aff` accent
- Glassmorphism via `backdrop-filter: blur(...)` on cards

**Canvas charts are 100% custom** — there is no Chart.js or any charting library. Do not import one. The chart code handles device pixel ratio, `niceScale()` for Y-axis, and `roundRect()` — all handwritten.

**Frontend state:**
```js
let summary, allTxns, allFixed, allAccs   // loaded via loadAll()
let selectedDashAccId = null               // current account filter
```

**After any mutating API call:**
```js
await loadAll();
renderDash();          // or renderTransactions(), renderFixed(), renderAccounts()
```

**Color picker pattern:** stores the selected hex in `<input type="hidden" id="{prefix}_color_val">`. Read it with `document.getElementById(prefix+'_color_val').value`.

**Preset colors** (used for both default account colors and picker swatches):
```js
['#4a90f8','#32d74b','#bf5af2','#5ac8fa','#ff9f0a','#ff453a','#ff6b6b','#ffd60a']
```

---

## Current Branch Context (`revamp`)

This branch is the result of a major refactor from a **single-currency** model to a **multi-account** model. Key artifacts of the refactor:

- Functions prefixed `modern_` were written during the refactor to replace the old single-currency logic. The "modern" prefix has no significance beyond that — it just means "newer implementation."
- The `LEGACY_ACCOUNT_BANKS` map and the `init_data()` migration code exist to bootstrap old installations that only had currency-keyed balances in `config`, not an `accounts` sheet.
- The `config` sheet balance keys (`balance_krw`, etc.) are now **only kept for legacy compatibility** — the real source of truth is the `accounts` sheet.
- Legacy account IDs (`'krw'`, `'uyu'`, `'usd'`) coexist with new `'acct_*'` IDs. Code that checks `if account_id in CURRENCIES` is handling the legacy path.

---

## What NOT to Do

These rules are specific to this codebase — not generic advice.

1. **Do not acquire `XLSX_LOCK` in a function that calls `set_balance()`** — `set_balance()` acquires the lock internally, causing a deadlock. `_add_txn()` handles this by calling `set_balance()` before re-acquiring the lock for the transaction write.

2. **Do not call `round_currency()` with uppercase currency codes** — `'KRW'` will raise `ValueError`. All internal currency keys are lowercase. Use `_currency_id(val)` to normalize first.

3. **Do not read balances from the `config` sheet** — use `get_balances()` which reads from `accounts`. The config keys are a legacy mirror only.

4. **Do not iterate forward when deleting Excel rows** — always iterate `range(ws.max_row, 1, -1)` backwards. Forward deletion shifts indices and skips rows.

5. **Do not use `'income'`, `'debit'`, or any other string for transaction type** — only `'fund'` and `'expense'` are valid. These are compared as literals throughout both backend and frontend.

6. **Do not import Chart.js or any charting library** — the canvas charts are intentionally handwritten. Adding a library would break the chart rendering code.

7. **Do not create a requirements.txt and add packages to it** — the project has no dependency management file by design. Document any new dependencies in README.md only.

8. **Do not use `_next_id(ws)` for account IDs** — accounts use `_new_account_id(existing_ids)` which generates `'acct_' + token_hex(4)`. `_next_id` only works for integer-ID sheets (transactions, fixed_payments).

9. **Do not call `wb.save()` outside the lock** — save must happen inside `with XLSX_LOCK` to prevent concurrent writes. Always load, mutate, and save within the same `with` block.

10. **Do not use Flask blueprints, separate route files, or a services layer** — the architecture is deliberately monolithic. Adding structure not present in the existing code will create inconsistency.

11. **Do not add server-side validation for amounts against current balance** — the app allows going negative. `_add_txn()` does not check that the account has sufficient balance before subtracting.
