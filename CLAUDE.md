# ClariFi — Project Expert Guide

## Stack & Versions

| Component | Version | Notes |
|-----------|---------|-------|
| Python | 3.13.13 | cpython, Windows |
| Flask | 3.1.3 | `app.secret_key` regenerated on each restart unless `SECRET_KEY` env var set |
| openpyxl | 3.1.5 | Runtime dep besides Flask; no ORM, no SQLite |
| Werkzeug | (Flask dep) | Dev server only — `debug=False`, binds to `127.0.0.1` only |
| pywebview | — | **Desktop build only.** Used by `launcher.py` to host the Flask app in a native window. Not imported by `app.py` itself. |
| PyInstaller | — | **Build-tool only.** Bundles Python + Flask + app code into `dist/ClariFi/ClariFi.exe`. Not a runtime dep. |
| Inno Setup 6 | — | **Build-tool only.** Wraps the PyInstaller bundle into the single `Output\ClariFi-Setup-<v>.exe` installer. |
| Frontend | Vanilla JS | No npm, no bundler, no Chart.js — canvas charts are **fully handwritten** |

No `requirements.txt` exists. For runtime: `pip install flask openpyxl`. For building the installer: `pip install pyinstaller pywebview Pillow` + Inno Setup 6. See [BUILD.md](BUILD.md).

---

## Folder Structure

```
basic-personal-finances-tracker/
├── app.py                   ← Entire backend: config, models, all routes (~1030 lines)
├── templates/
│   └── index.html           ← Entire frontend: CSS, HTML, JS (~2015 lines)
├── finance_data.xlsx        ← Dev-mode database, auto-created, gitignored
├── Start.bat                ← Kills port 5000, then runs python app.py
├── launcher.py              ← Desktop entry point: starts Flask in a thread, opens a pywebview window
├── ClariFi.spec             ← PyInstaller config (bundles templates/, sets icon, hides console)
├── ClariFi.iss              ← Inno Setup script for the .exe installer
├── BUILD.md                 ← Step-by-step build/release instructions
├── clarifi.ico              ← App icon (multi-size, committed to repo)
└── CLAUDE.md
```

No blueprints, no separate routes file, no models file, no services layer — everything lives in `app.py`. The desktop-build files (`launcher.py`, `ClariFi.spec`, `ClariFi.iss`, `BUILD.md`, `clarifi.ico`) are inert during normal `python app.py` runs.

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

Later `modern_*` functions (added during multi-account refactor) use `app.add_url_rule()` at the bottom of the file (around lines 947–960):

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
| PUT | `/api/transactions/<int:tid>` | `modern_edit_txn` |
| POST | `/api/balance` | `modern_set_balance` |
| GET | `/api/export` | `modern_export` |
| POST | `/api/import` | `modern_import` |
| POST | `/api/clear` | `modern_clear` |
| GET | `/api/fixed` | `modern_fixed` |
| POST | `/api/fixed` | `modern_create_fixed` |
| PUT | `/api/fixed/<int:fid>` | `modern_edit_fixed` |
| GET | `/api/accounts` | `modern_accounts` |
| POST | `/api/accounts` | `modern_create_account` |
| PUT | `/api/accounts/<account_id>` | `modern_edit_account` |
| DELETE | `/api/accounts/<account_id>` | `modern_delete_account` |
| DELETE | `/api/accounts/<account_id>/permanent` | `modern_permanent_delete_account` |
| GET | `/api/version/check` | `api_version_check` |

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

## Legacy / Multi-Account Migration Notes

The app was originally **single-currency** and was refactored into a **multi-account** model. The refactor lives on `main` now; artifacts:

- Functions prefixed `modern_` were written during the refactor to replace the old single-currency logic. The "modern" prefix has no significance beyond "newer implementation." Routes added *after* the refactor (e.g. `modern_edit_txn`, `modern_edit_fixed`, `modern_clear`, `api_version_check`) follow the same `add_url_rule` registration style.
- The `LEGACY_ACCOUNT_BANKS` map and the `init_data()` migration code exist to bootstrap old installations that only had currency-keyed balances in `config`, not an `accounts` sheet.
- The `config` sheet balance keys (`balance_krw`, etc.) are now **only kept for legacy compatibility** — the real source of truth is the `accounts` sheet.
- Legacy account IDs (`'krw'`, `'uyu'`, `'usd'`) coexist with new `'acct_*'` IDs. Code that checks `if account_id in CURRENCIES` is handling the legacy path.

---

## Desktop App / Installer

The app ships as a Windows installer (`Output\ClariFi-Setup-<version>.exe`) that bundles Python, Flask, openpyxl, and the app code into a single download. End users do not need Python installed.

**Two-stage build pipeline** — both stages are run from the project root by the developer per release. See [BUILD.md](BUILD.md) for the full commands.

1. **PyInstaller** (`python -m PyInstaller --noconfirm ClariFi.spec`) → outputs `dist/ClariFi/ClariFi.exe` plus supporting DLLs/`.pyd`s in the same folder. `ClariFi.exe` is the app itself, but it needs the surrounding folder to run.
2. **Inno Setup** (`ISCC.exe ClariFi.iss`) → wraps `dist/ClariFi/` into the single `Output\ClariFi-Setup-<version>.exe` installer.

### `launcher.py` — desktop entry point

When packaged as an `.exe`, the entry point is **`launcher.py`**, not `app.py`. It:
1. Picks a random free localhost port (so port 5000 is no longer assumed).
2. Starts Flask on that port in a daemon thread (`use_reloader=False`).
3. Waits for `/` to respond.
4. Opens a `pywebview` native window pointing at `http://127.0.0.1:<port>/`.

Closing the window exits the process; the daemon thread dies with it.

### `DATA_PATH` resolves differently in frozen vs dev

`app.py` defines `_default_data_path()` which checks `sys.frozen`:

- **Dev mode** (`python app.py`): `DATA_PATH = 'finance_data.xlsx'` next to the script.
- **Installed exe** (`sys.frozen == True`): `DATA_PATH = %APPDATA%\ClariFi\finance_data.xlsx`. The directory is created automatically.
- The `DATA_PATH` env var always overrides both.

This is why the installed app does **not** touch `Program Files\ClariFi\` for data — that path is read-only without admin rights. User data must stay in `%APPDATA%`.

### Versioning & in-app updates

- `APP_VERSION` constant near the top of `app.py` is the **single source of truth** for the installed version. Bump it (and the matching `MyAppVersion` in `ClariFi.iss`) on every release.
- `GITHUB_REPO` constant points to `federicoroldos/basic-personal-finances-tracker`.
- `GET /api/version/check` (handler: `api_version_check`) hits `https://api.github.com/repos/<repo>/releases/latest`, compares semver via `_parse_semver()` (strips leading `v`, pads to 3 components), and returns `{ok, current, latest, update_available, installer_url, release_url, notes, ...}`. It picks the first `.exe` or `.msi` asset on the release as `installer_url`.
- The **Updates** sidebar entry in `index.html` calls this endpoint via `checkForUpdates()` and renders either a "you're up to date" panel or a Download Installer / Release Notes pair of buttons.
- Releases are tagged on `main` (`git tag v0.1.0 && git push origin v0.1.0`) and published on GitHub Releases with the installer attached as an asset. Branch doesn't matter — tags do.

### Icon

`clarifi.ico` is committed to the repo and referenced by both `ClariFi.spec` (`icon=`) and `ClariFi.iss` (`SetupIconFile=`). Multi-size ICO (16, 24, 32, 48, 64, 128, 256). Generated programmatically — see commit history if it ever needs regeneration.

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

12. **Do not hardcode port 5000** — the dev server uses 5000 but `launcher.py` picks a random free port at runtime for the installed exe. Anything that assumes `localhost:5000` will break in the desktop build. Read `request.host_url` server-side, or use relative URLs client-side (already the convention — all `fetch('/api/...')` calls are relative).

13. **Do not write data files next to the executable** — in the frozen build, the app lives in `Program Files\ClariFi\` which is read-only without admin. Always go through `DATA_PATH` (which `_default_data_path()` routes to `%APPDATA%\ClariFi\` when frozen). If you add a new persistent file, follow the same pattern.

14. **Do not bump `APP_VERSION` without also bumping `MyAppVersion` in `ClariFi.iss`** — they must match. The in-app Updates tab compares the running `APP_VERSION` against GitHub releases, and `MyAppVersion` decides the installer's filename and Add/Remove Programs entry. Mismatch causes user-visible confusion. **Every version bump must also create and push a matching `vX.Y.Z` git tag** — without the tag, the in-app updater has no GitHub release to discover, so the bump is invisible to users. After committing the bump, run `git tag v<new-version> <main-commit>` and `git push origin v<new-version>`.

15. **Do not add `Co-Authored-By: Claude` (or similar) trailers to commits in this repo** — the user wants only their own name on the contributors list. Standard git commit messages, no co-author trailer.

16. **Push `build` before `main`, and never push the version tag before both branches are pushed** — when a release touches both branches (typical for a version bump), the order is fixed: (1) commit + push `build`, (2) commit + push `main`, (3) create the `vX.Y.Z` tag on the `main` commit and push the tag. The release workflow checks out `main` for app source but pulls `ClariFi.spec`, `ClariFi.iss`, `launcher.py`, and `clarifi.ico` from `origin/build` — pushing the tag before `build` is up to date means the workflow ships an installer with the old `MyAppVersion` and old launcher.

17. **Do not freestyle the GitHub release title or body** — every GitHub release must follow this exact format. The title is `ClariFi <X.Y.Z>` (no `v` prefix). The body is:

    ```markdown
    ## What's new
    - <user-facing change>
    - <user-facing change>
    - <user-facing change>

    ## Install
    Download `ClariFi-Setup-<X.Y.Z>.exe` below and run it.
    ```

    Bullets describe **user-visible** behavior, not internal refactors or version bumps. The installer filename in the Install section must match the actual asset name (which is driven by `MyAppVersion` in `ClariFi.iss`).

    **The release workflow does NOT set these for you.** `softprops/action-gh-release` auto-creates the release using the tag name (`v0.1.6`) as the title and the commit message as the body — both wrong by this convention. So **every** `git push origin v<X.Y.Z>` must be followed by a `gh release edit v<X.Y.Z> --title "ClariFi <X.Y.Z>" --notes "..."` call to overwrite the auto-generated title and body. Pushing the tag without immediately running `gh release edit` leaves the release in the wrong format. Treat the `gh release edit` step as part of the release push order, not an optional follow-up.
