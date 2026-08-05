# ClariFi - Project Expert Guide

## Stack & Versions

| Component | Version | Notes |
|-----------|---------|-------|
| Python | 3.13.13 | cpython, Windows |
| Flask | 3.1.3 | `app.secret_key` regenerated on each restart unless `SECRET_KEY` env var set |
| openpyxl | 3.1.5 | Runtime dep besides Flask; no ORM, no SQLite |
| Werkzeug | (Flask dep) | Dev server only - `debug=False`, binds to `127.0.0.1` only |
| pywebview | - | **Desktop build only.** Used by `launcher.py` to host the Flask app in a native window. Not imported by `app.py` itself. On Linux it uses the GTK/WebKitGTK backend (`PYWEBVIEW_GUI=gtk`). |
| PyInstaller | - | **Windows build-tool only.** Bundles Python + Flask + app code into `dist/ClariFi/ClariFi.exe`. Not used for the Linux build, not a runtime dep. |
| Inno Setup 6 | - | **Windows build-tool only.** Wraps the PyInstaller bundle into the single `Output\ClariFi-Setup-<v>.exe` installer. |
| Linux `.deb` | - | **Thin package, no bundler.** Ships app source + a vendored `pywebview`/`pypdf`, declares everything else as apt `Depends`. Built by `build-deb.sh`. |
| Frontend | Vanilla JS | No npm, no bundler, no Chart.js - canvas charts are **fully handwritten** |

No `requirements.txt` exists. For runtime: `pip install flask openpyxl`. For building the Windows installer: `pip install pyinstaller pywebview Pillow` + Inno Setup 6. The Linux `.deb` needs no build tools beyond `dpkg-deb`. See [BUILD.md](BUILD.md).

---

## Folder Structure

```
basic-personal-finances-tracker/
├── app.py                   ← Entire backend: config, models, all routes (~2020 lines)
├── templates/
│   └── index.html           ← Entire frontend: CSS, HTML, JS (~3250 lines)
├── finance_data.xlsx        ← Dev-mode database, auto-created, gitignored
├── Start.bat                ← Kills port 5000, then runs python app.py
├── launcher.py              ← Desktop entry point: starts Flask in a thread, opens a pywebview window  [release branch]
├── ClariFi.spec             ← PyInstaller config for Windows (bundles templates/, sets icon, hides console)  [release branch]
├── ClariFi.iss              ← Inno Setup script for the .exe installer  [release branch]
├── build-deb.sh             ← Builds the thin Linux .deb  [release branch]
├── run.sh                   ← Linux launcher wrapper (GTK backend, XDG data path)  [release branch]
├── clarifi.desktop          ← Linux applications-menu entry  [release branch]
├── BUILD.md                 ← Step-by-step build/release instructions  [release branch]
├── clarifi.ico              ← App icon (multi-size)  [release branch; shared by both builds]
├── android/                 ← Native Android app (Kotlin + Jetpack Compose); see its own section
├── PRIVACY.md               ← Privacy policy; Play requires this URL to stay reachable
└── CLAUDE.md
```

No blueprints, no separate routes file, no models file, no services layer - everything lives in `app.py`. The desktop-build files (`launcher.py`, `ClariFi.spec`, `ClariFi.iss`, `clarifi.ico` for Windows; `clarifi.desktop`, `run.sh`, `build-deb.sh` for Linux; plus `BUILD.md`) live on the **`release`** branch, not `main`, and are inert during normal `python app.py` runs. The CI workflow pulls them from `origin/release` at build time.

---

## Excel "Database" - How It Actually Works

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
    wb = _load_wb()          # always the local finance_data.xlsx
    ws = wb['sheet_name']
    # ... read or mutate ws ...
    _save_wb(wb)             # must be inside the lock
```

`XLSX_LOCK` is a `threading.Lock()` - it is **not reentrant**. Never acquire it from a call stack that already holds it (causes deadlock). `set_balance()` acquires `XLSX_LOCK` internally - do not call it from inside an existing `with XLSX_LOCK` block.

**Persist through `_save_wb(wb)`, never `wb.save(DATA_PATH)` directly.** `_save_wb()` and `_load_wb()` are the single load/save boundary and **always** read/write the local `finance_data.xlsx` - even when a cloud database is configured. (They used to rebuild the workbook from Postgres on every request when cloud sync was on; that was unusably slow, so cloud is now a manual Push/Pull backup, see "Cloud Sync" below.) The only places that legitimately call `wb.save(DATA_PATH)` are `_save_wb`/`_write_local_xlsx` themselves.

### Cloud Sync (optional Postgres backup/sync)

The app **always** works on the local xlsx for speed; the cloud is a manual backup/sync target, never a live per-request backend. `pg8000` is never imported unless you Push/Pull (it is imported lazily inside `_pg_connect`). State lives in `cloud_config.json` next to `DATA_PATH` (`{"dsn", "last_push", "last_pull"}`) - the connection string is **local only, never stored in the cloud**. `CLARIFI_CLOUD_DSN` env var overrides it. `cloud_configured()` (a DSN is saved/set) gates whether Push/Pull are offered; it does **not** change how data is read or written.

- Postgres tables mirror `SHEETS` one-to-one, prefixed `clarifi_` (`clarifi_accounts`, …). Column types are keyed by **(sheet, column)** via `_PG_INT_COLS`/`_PG_FLOAT_COLS`/`_PG_BOOL_COLS`, because the same name differs across sheets: `transactions.id`/`fixed_payments.id` are `INTEGER` but **`accounts.id` is `TEXT`** (account ids are strings like `'usd'`/`'acct_*'`). Everything else is `TEXT`.
- **`_pg_ensure_schema` enables Row Level Security on every table and adds no policies, and that is the finished state, not a half-done one.** Supabase publishes the `public` schema over a REST API that RLS is the only gate on, so a table without it can be read and rewritten by anyone holding the project's anon key. No policy means the API can see nothing; ClariFi is unaffected because it connects as the table owner and owners bypass RLS. Do not "finish" this by writing a permissive policy, and do not set `FORCE ROW LEVEL SECURITY`, which would revoke the owner's bypass and break Push and Pull. `PostgresCloud.ensureSchema` on Android does the same thing - change both together.
- Persist strategy is whole-DB last-write-wins: **Push** (`_pg_from_wb`) does `TRUNCATE` + bulk `INSERT` per table in one transaction (local overwrites cloud). **Pull** (`_wb_from_pg`) rebuilds the local xlsx from Postgres, backing up the old local file first via `_backup_local_xlsx`. No row-level merge; nothing syncs automatically, so a stale device can clobber newer cloud data on its next Push.
- Connectivity failures raise `CloudError`, surfaced by the one `@app.errorhandler(CloudError)` as a clean `503` (this is the only error handler in the app, intentionally narrow). No silent fallback.
- Routes `cloud_status`/`cloud_save`/`cloud_push`/`cloud_pull`/`cloud_forget` live in the cloud block at the end of `app.py`. `cloud_save` validates + persists the DSN (moves no data); `cloud_push`/`cloud_pull` move the whole workbook; `cloud_forget` clears the saved DSN (an env-var DSN can't be cleared this way).
- Build follow-up (lives on `release`): `pg8000` must be a PyInstaller `hiddenimport` in `ClariFi.spec`, and `python3-pg8000` an apt `Depends` (or vendored) for the `.deb`.

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

- **ID format**: Legacy accounts (created at init) use the currency code as ID - string `'krw'`, `'uyu'`, `'usd'`. New accounts use `'acct_' + secrets.token_hex(4)` (e.g., `'acct_3f4a8b2c'`).
- **Currency**: stored lowercase (`'krw'`, `'uyu'`, `'usd'`). Always normalize with `_currency_id(val)` before storing.
- **Archived = soft delete**: `archived=True` hides the account from the UI but preserves all data. Only archived accounts can be permanently deleted. `modern_restore_account` flips the flag back - archiving never recalculates anything, so a restored account returns with its balance and history untouched. Both directions are idempotent (restoring an active account is a `200`, not an error).
- **Permanent delete cascades**: removes all fixed_payments, all fixed_applied records, all transactions, and the balance_* config row (for legacy accounts).
- **Balance source**: always read from `accounts` sheet. The `config` sheet balance_* keys are legacy - `set_balance()` keeps them in sync for legacy account IDs, but `get_balances()` reads accounts, not config.

### Transaction

- **type**: only two valid string values: `'fund'` (income) and `'expense'`. Never `'income'`, never `'debit'`.
- **amount**: stored as positive float regardless of type. The sign is implied by `type`.
- **Deletion reverses balance**: deleting a `fund` subtracts; deleting an `expense` adds back. This is done in `modern_delete_txn()`.
- **account field**: stores the account ID string. Transactions for archived accounts are kept, but orphaned (no active account) transactions are skipped by `_annotate_txn()`.
- **Balance adjustment (POST /api/balance) does NOT create a transaction** - it directly updates the balance. This is intentional.

### Fixed Payment

- **ID**: integer, auto-incremented via `_next_id()`.
- **day**: integer 1-31, represents the day of month it's due.
- **Applied tracking**: `fixed_applied` sheet stores `(payment_id, year_month)` pairs. A payment is "applied" when that pair exists for the current month.
- **Due**: `day <= today_day AND NOT applied this month`, with the day first clamped to the month's
  last day (`_due_day_this_month` in `app.py`, `Dates.dueDayThisMonth` on Android). A payment on the
  31st has no 31st in April and none at all in February, and comparing the raw day meant it never
  came due, was never applied, and was silently skipped for that month. Both platforms clamp the
  same way - change them together.
- **Applying** creates an `expense` transaction and appends to `fixed_applied` - these are two separate writes, the applied record first, the transaction via `_add_txn`.
- **Undo matching**: finds the most recent expense transaction matching the fixed payment's name, account, and current month - not by transaction ID. This means if you manually add an expense with the same name/account, undo could accidentally delete it.

### Currency

```python
CURRENCIES = {
    'usd': {'code': 'USD', 'name': 'US Dollar',      'symbol': 'US$', 'decimals': 2, 'region': 'US'},
    ...  # 37 entries, mirrored by Currencies.ALL on Android
}
```

- Keys are **always lowercase**. Passing uppercase to `round_currency()` raises `ValueError`.
- `round_currency(currency, val)` - canonical rounding, takes lowercase string.
- `round_acc(account_id, val)` - shortcut that looks up the account's currency first.
- Most currencies round to 2 decimals; KRW, JPY, CLP and VND round to 0.
- **Declaration order is picker order.** `usd, eur, uyu, ars, krw` (the five the app shipped
  with) come first on both platforms, then the widely used ones. The desktop's two currency
  `<select>`s are rendered from the dict by Jinja (`index()` passes `currencies=CURRENCIES`), so
  adding one there is a single edit; Android's chip row reads `Currencies.ALL`.
- **`region` is the ISO 3166 country whose flag the account tile shows.** Both platforms build the
  flag emoji from it out of the two regional indicator letters, so there is no artwork to keep in
  step: `Currency.flag` on Android, `accFlag()` in `index.html`. Android and Linux have the glyphs
  and draw a flag; **Windows does not** (Segoe UI Emoji carries no flags), so the desktop shows the
  region's two letters there, which is what the tile showed before flags existed. That trade was
  made deliberately, with the rendered comparison in front of the user - the alternative was
  hand-drawn SVG per currency. Do not "fix" it by reintroducing the artwork; the fix, if it is ever
  wanted, is bundling a flag font with the installer.

---

## Route Registration - Two Styles

Early routes use `@app.route` decorator:

```python
@app.route('/api/summary')
def api_summary(): return jsonify(build_summary())
```

Later `modern_*` functions (added during multi-account refactor) use `app.add_url_rule()` at the bottom of the file (around lines 947-960):

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
| POST | `/api/accounts/<account_id>/restore` | `modern_restore_account` |
| DELETE | `/api/accounts/<account_id>/permanent` | `modern_permanent_delete_account` |
| POST | `/api/transfer` | `modern_transfer` |
| POST | `/api/receipt/scan` | `receipt_scan` |
| GET | `/api/receipt/config` | `receipt_config_get` |
| POST | `/api/receipt/config` | `receipt_config_set` |
| POST | `/api/statement/scan` | `statement_scan` |
| POST | `/api/statement/import` | `statement_import` |
| GET | `/api/fxrates` | `api_fxrates` |
| GET | `/api/version/check` | `api_version_check` |
| POST | `/api/version/download` | `api_version_download` |
| GET | `/api/version/download/progress` | `api_version_download_progress` |
| POST | `/api/version/install` | `api_version_install` |
| GET | `/api/cloud/status` | `cloud_status` |
| POST | `/api/cloud/save` | `cloud_save` |
| POST | `/api/cloud/push` | `cloud_push` |
| POST | `/api/cloud/pull` | `cloud_pull` |
| POST | `/api/cloud/forget` | `cloud_forget` |

---

## Error Handling Pattern

**All API responses follow this exact shape:**

```python
# Success
return jsonify({'ok': True, ...})

# Error
return jsonify({'ok': False, 'error': 'descriptive message'}), HTTP_STATUS
```

- `400` - bad input (invalid amount, unknown currency, missing required field)
- `404` - resource not found (account ID, transaction ID, fixed payment ID)
- No 500 handlers, no global `@app.errorhandler`, no logging.

**Input parsing pattern:**

```python
try:
    currency = _currency_id(data.get('currency'))
    balance = round_currency(currency, data.get('balance') or 0)
except (TypeError, ValueError):
    return jsonify({'ok': False, 'error': 'invalid currency or balance'}), 400
```

Always catch `(TypeError, ValueError)` together - openpyxl can return `None` for missing cells, which causes `TypeError` on float conversion.

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

Single `templates/index.html` file - no build step, no npm, no bundler.

**Design tokens (CSS variables on `:root` and `[data-theme='light']`):**
- Dark: `#08080a` background, `#10b981` (green) accent
- Light: `#ececef` background, `#059669` (green) accent
- Solid card surfaces via `--glass*` variables (no `backdrop-filter`; despite the name, the UI is not glassmorphism)

**Canvas charts are 100% custom** - there is no Chart.js or any charting library. Do not import one. The chart code handles device pixel ratio, `niceScale()` for Y-axis, and `roundRect()` - all handwritten.

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
- The `config` sheet balance keys (`balance_krw`, etc.) are now **only kept for legacy compatibility** - the real source of truth is the `accounts` sheet.
- Legacy account IDs (`'krw'`, `'uyu'`, `'usd'`) coexist with new `'acct_*'` IDs. Code that checks `if account_id in CURRENCIES` is handling the legacy path.

---

## Desktop App / Installers

The app ships two packages, both attached to the same GitHub Release by `.github/workflows/release.yml` (jobs `release-windows` then `release-linux`). The build-tooling files live on the **`release`** branch; the workflow checks out `main` for app source and pulls them from `origin/release`. See [BUILD.md](BUILD.md) for full commands.

### Windows installer (`Output\ClariFi-Setup-<version>.exe`)

Bundles Python, Flask, openpyxl, and the app code into a single download. End users do not need Python installed. Two stages:

1. **PyInstaller** (`python -m PyInstaller --noconfirm ClariFi.spec`) → outputs `dist/ClariFi/ClariFi.exe` plus supporting DLLs/`.pyd`s in the same folder. `ClariFi.exe` is the app itself, but it needs the surrounding folder to run.
2. **Inno Setup** (`ISCC.exe ClariFi.iss`) → wraps `dist/ClariFi/` into the single `Output\ClariFi-Setup-<version>.exe` installer.

### Linux package (`clarifi_<version>_amd64.deb`)

A **thin** package: it bundles neither Python nor a web engine, mirroring how the Windows build reuses the system WebView2. `build-deb.sh` ships the app source plus a small vendored `pywebview`/`pypdf` into `/opt/clarifi`, and declares the rest (Python, GTK/WebKitGTK typelibs, Flask, openpyxl, Pillow) as apt `Depends` so `apt install ./clarifi_*.deb` resolves them. This keeps the `.deb` at ~1.5 MB (the Qt-bundled approach was ~140 MB - do not reintroduce it). `run.sh` is the launch wrapper: it forces `PYWEBVIEW_GUI=gtk`, sets `PYTHONPATH` to `/opt/clarifi` + its `vendor/`, and points `DATA_PATH` at the XDG data dir. `/usr/bin/clarifi` symlinks to it; `clarifi.desktop` is the menu entry. PyInstaller is **not** used on Linux.

### `launcher.py` - desktop entry point

In both packaged builds the entry point is **`launcher.py`**, not `app.py` (the Windows `.exe` runs it frozen; the Linux `.deb` runs it via `run.sh` under the system Python). It:
1. Picks a random free localhost port (so port 5000 is no longer assumed).
2. Starts Flask on that port in a daemon thread (`use_reloader=False`).
3. Waits for `/` to respond.
4. Opens a `pywebview` native window pointing at `http://127.0.0.1:<port>/`.

Closing the window exits the process; the daemon thread dies with it.

### `DATA_PATH` resolves differently in frozen vs dev

`app.py` defines `_default_data_path()` which checks `sys.frozen`:

- **Dev mode** (`python app.py`): `DATA_PATH = 'finance_data.xlsx'` next to the script.
- **Installed exe** (`sys.frozen == True`, Windows): `DATA_PATH = %APPDATA%\ClariFi\finance_data.xlsx`. When frozen on non-Windows it falls back to the XDG path (`$XDG_DATA_HOME` or `~/.local/share`)`/ClariFi/`. The directory is created automatically.
- The `DATA_PATH` env var always overrides both.

This is why the installed app does **not** write next to the executable - `Program Files\ClariFi\` (Windows) and `/opt/clarifi` (Linux) are read-only without elevation. User data must stay in `%APPDATA%` / `~/.local/share`. **The Linux `.deb` is not frozen**, so it does not hit the `sys.frozen` branch: `run.sh` sets `DATA_PATH` explicitly to the XDG path instead.

### Versioning & in-app updates

- `APP_VERSION` constant near the top of `app.py` is the **single source of truth** for the installed version. Bump it (and the matching `MyAppVersion` in `ClariFi.iss`) on every release.
- `GITHUB_REPO` constant points to `federicoroldos/clarifi`.
- `GET /api/version/check` (handler: `api_version_check`) hits `https://api.github.com/repos/<repo>/releases/latest`, compares semver via `_parse_semver()` (strips leading `v`, pads to 3 components), and returns `{ok, current, latest, update_available, installer_url, release_url, notes, ...}`. It picks the first `.exe` or `.msi` asset on the release as `installer_url`, so the in-app updater is Windows-only; Linux users update by reinstalling the newer `.deb` (the release also carries the `.deb`, which the updater ignores).
- The **Updates** sidebar entry in `index.html` calls this endpoint via `checkForUpdates()` and renders either a "you're up to date" panel or a Download Installer / Release Notes pair of buttons.
- Releases are tagged on `main` (`git tag v0.1.0 && git push origin v0.1.0`) and published on GitHub Releases with the installer attached as an asset. Branch doesn't matter - tags do.

### Icon

`clarifi.ico` is committed to the `release` branch and referenced by both `ClariFi.spec` (`icon=`) and `ClariFi.iss` (`SetupIconFile=`). Multi-size ICO (16, 24, 32, 48, 64, 128, 256). Generated programmatically - see commit history if it ever needs regeneration. The Linux build derives a 256×256 `clarifi.png` from it on the fly (in CI) for the `.deb` menu icon.

---

## Android App (`android/`)

A **native** Kotlin/Compose app - not a WebView. It lives on `main` in `android/` and is
independent of the Flask backend: it has its own Room database and its own copy of the business
rules, ported 1:1 from `app.py`.

### Build

```
cd android && ./gradlew assembleDebug        # or testDebugUnitTest / connectedDebugAndroidTest
```

AGP 8.9.2, Kotlin 2.1.20, Gradle 8.14.3, `compileSdk`/`targetSdk` 36, `minSdk` 26. **JDK 21 is
required** - AGP rejects newer JDKs, so on this machine set
`JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`. `android/local.properties` (gitignored)
points at the SDK.

### Architecture

Single `:app` module, organised by feature, with **hand-wired dependencies** in `AppContainer`
(no Hilt/KSP DI - the graph is one readable file). One `ViewModel` per screen exposing a single
`StateFlow<UiState>`; Room emits `Flow`, so the UI updates itself after every mutation.

- `core/` - currencies + rounding, enums, categories, ids, dates. Pure Kotlin, no Android imports.
- `data/db/` - Room entities that mirror `SHEETS` **column for column**. These *are* the app's
  models; there is no second set of domain twins to map to.
- `data/repo/` - the business rules, plus `SummaryRepository` (a port of `build_summary`).
- `data/ai/` - Groq/Gemini/Claude client, prompts copied verbatim from `app.py`, receipt and
  statement scanners.
- `data/backup/` - JSON export/import in the desktop's `version: 2` format.
- `data/cloud/` - manual Supabase Push/Pull over the Postgres wire protocol (see below), and the
  row mapping to the desktop's `clarifi_*` tables.
- `data/updates/` - reads the latest GitHub release for the About screen's changelog.
- `ui/` - theme (tokens ported from the CSS), `ClariFiIcons` (the web's SVGs as `ImageVector`),
  charts drawn by hand on Canvas, and one package per screen. `ui/tutorial/` is the guided tour:
  a spotlight over the running app, shown **once, on a fresh install only**
  (`SettingsStore.walkthroughSeen`, written whether it was finished or skipped). There is no way to
  replay it; `ui/help/HelpScreen.kt` (the drawer's How ClariFi works) is the written version, and
  is what someone who skipped it reads. Every topic the tour covers has an entry there, worded the
  same way - change one and change the other. A screen joins the tour by putting
  `Modifier.tutorialTarget(...)` on a
  real control; the overlay cuts a hole there, seals the rest of the screen, and waits for that
  control's own tap. It never simulates a press or navigates itself, so there is no second code
  path to keep in step - but the steps do name real gestures, so a screen that changes one has to
  change its step in `TutorialSteps.kt` too. A step marked `suppressAction` swallows the control's
  click instead, for the ones that would take over the screen (the + opens a sheet in a window
  above the tour; applying a fixed payment would write a real transaction). Anything the tour can
  open on top of itself has to know about it: `NotificationPermissionPrompt` waits for the tour to
  finish for exactly that reason.
- `seedExamples()` writes three transactions and one fixed payment on a brand new install, before
  the tour has ever run. Half the tour's steps point at rows, and on an empty ledger they fall back
  to a plain card - which is precisely the install every first-time user has. It is deliberately
  *not* called from Clear All Data (that shares `seedIfEmpty`, accounts only): a button that says
  it erases everything has to leave the ledger empty.

### Rules specific to the Android app

1. **Round with `BigDecimal` + `HALF_EVEN`, never `HALF_UP`.** Python's `round()` is
   banker's rounding; `HALF_UP` drifts a cent from the desktop on ties. `CurrenciesTest` pins this.
2. **Do not add a charting library** - same rule as the web. The Canvas charts are handwritten and
   `niceScale()` is a literal port.
3. **Do not add an HTTP client or a JSON library to `main`.** `HttpURLConnection` and `org.json`
   cover the AI endpoints and the GitHub release check. JVM unit tests *do* need `org.json:json`
   because `android.jar`'s copy is a stub that throws. The one third-party runtime dependency is
   jasync, and only because Postgres cannot be spoken any other way (see Cloud sync).
4. **The AI prompts in `data/ai/Prompts.kt` are copies of the ones in `app.py`.** Change both or
   the same receipt gets categorised differently on each platform.
5. **The AI key lives in `SecretStore` (EncryptedSharedPreferences) only.** Never in Room, never
   in an export.
6. **Every balance change goes through `AccountRepository.applyDelta(accountId, …)`**, which
   re-reads the balance inside the transaction. Passing a caller-held `Account` loses updates when
   two deltas land in one operation (both transfer legs, or an edit's reverse-then-reapply).
7. **Version comes from `-PclarifiVersion`**, which CI sets from the tag. The literal in
   `app/build.gradle.kts` is only a local fallback and must track `APP_VERSION`.
8. **UI copy is the desktop's copy.** The screens were written side by side with `index.html` and
   the wording, the emphasis and the category emoji (`CAT_ICONS`) are ported deliberately. Change
   one platform's text and change the other, or they drift apart a sentence at a time.
9. **Do not commit `app/src/main/assets/adi-registration.properties`.** That file carries the Play
   developer-verification token for the account, and this repo is public. Create it, build the
   verification APK, upload it, delete it; the token can always be copied again from the console.

10. **`clarifi_secrets.xml` must stay out of every backup, and `SecretStore` must survive being
    unable to open it.** The exclusions live in `res/xml/backup_rules.xml` (Android 11 and below)
    and `res/xml/data_extraction_rules.xml` (12 and up, both `cloud-backup` and `device-transfer`),
    wired from the manifest. `EncryptedSharedPreferences` is opened by a Keystore master key that
    never leaves the device that made it, so a restored file arrives with nothing that can decrypt
    it and every read throws `AEADBadTagException`. The store is built during startup, so that is
    not a failed read, it is an app that cannot be opened: 0.3.2 shipped this way and crashed on
    every Play install that restored a backup. Keep both halves. The exclusion prevents the common
    case, and the recovery in `SecretStore.open` covers a Keystore reset, which no manifest can.
    Do not widen the rules to exclude the database as well: the ledger lives nowhere else, and
    restoring it is the point.

### Cloud sync: same connection string, different driver

The phone syncs against the **same Postgres database and the same `clarifi_*` tables** as the
desktop, from the **same connection string**. Push and Pull mean what they mean on the desktop:
manual, whole-database, last write wins.

**The driver is jasync (`com.github.jasync-sql:jasync-postgresql`), never pgjdbc.** pgjdbc cannot
open a connection under ART at all, and no property avoids it: `PGStream.setMaxResultBuffer` calls
`PGPropertyMaxResultBufferParser.parseProperty` on every connection, which calls `adjustResultSize`,
which touches `java.lang.management.ManagementFactory` - a class Android does not have. Verified on
an emulator against the real project, with and without `maxResultBuffer` set. **Do not try to
re-add pgjdbc.**

Things that follow from this, all of them load-bearing:

- **R8 needs the rules in `proguard-rules.pro` and each one has a scar.** Netty registers leak
  exclusions by method *name* (`toLeakAwareBuffer`), so `io.netty.buffer.**` members must survive
  renaming. Netty reads a handler's message type from its generic signature, so `Signature` must be
  kept **and** jasync's classes must not be merged away (`-keep class com.github.jasync.**`). Each
  of these fails only in a minified build, with a message that names nothing recognisable. The
  release APK must be smoke-tested against a real database before shipping.
- Android has **no `javax.security.sasl`**, and SCRAM's *failure* path constructs a
  `SaslException`. A wrong password therefore arrives as a `NoClassDefFoundError`, not an
  exception, which is why `PostgresCloud.connected` catches `Throwable`.
- The phone **can create the schema** (`ensureSchema`, mirroring `_pg_ensure_schema`), so it no
  longer needs the desktop to go first. A Push is a real transaction: TRUNCATE + INSERT for every
  table, all or nothing, exactly like `_pg_from_wb`.
- A Pull downloads everything before touching Room, writes a JSON copy to `filesDir/backups`, then
  replaces the local data in one `withTransaction`.
- The schema lives in `data/cloud/CloudRows.kt` (`CloudSchema`) and mirrors `SHEETS` plus
  `_PG_*_COLS` column for column. **Change it and app.py together**, or one platform starts
  misreading the other's rows.
- The connection string lives in `SecretStore` (EncryptedSharedPreferences), never in Room and
  never in an export, as the desktop keeps its own in `cloud_config.json`. `config.ai_api_key` is
  filtered out of both directions.
- Cost: about **+1.3 MB** on the release APK (jasync, Netty and Joda-Time after shrinking).

### Release

Two destinations, from the same tag push.

**GitHub Releases.** The `release-android` job runs the unit tests, builds a signed APK and attaches
`ClariFi-<version>.apk` to the same Release as the `.exe` and the `.deb`. Signing reads
`CLARIFI_KEYSTORE` / `CLARIFI_KEYSTORE_PASSWORD` / `CLARIFI_KEY_ALIAS` / `CLARIFI_KEY_PASSWORD` from
the environment, fed by the `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` repo secrets.

**Google Play.** The same job also builds the App Bundle; `publish-play` then uploads it at 100%,
with the R8 mapping so crash reports in the console are readable. It needs one more secret,
`PLAY_SERVICE_ACCOUNT_JSON` (the whole JSON key of a service account with "Release apps to testing
tracks" and "Release to production" on this app). **Without that secret the job fails in
milliseconds with `Unknown error occurred`**, which reads like a Play rejection and is not one: the
action is constructing a client from an empty credential. Check `gh secret list` before believing
anything else the message suggests.

- **The track is the repository variable `PLAY_TRACK`** (`gh variable set PLAY_TRACK --body
  production`), falling back to `internal` when unset. Moving from a testing track to production is
  a setting, not a commit, so the same tag flow works before and after Play opens production up.
- **Release notes ship in the repo**, at `distribution/whatsnew/whatsnew-<locale>` (e.g.
  `whatsnew-en-US`), 500 characters each, one file per language the listing has. Play shows them
  verbatim as What's new. Update them with the version bump: a stale file means the new release
  goes out describing the previous one. A locale with no file is skipped; a file for a locale the
  listing does not have is an upload error. `publish-play` checks out only this directory, so the
  binary still comes from the build job and never from a rebuild.
- The bundle is **handed between jobs as an artifact, never rebuilt**, so the binary Google reviews
  is the one whose tests passed.
- `publish-play` is a separate job so it can be re-run alone when Play rejects an upload for a
  console-side reason, without cutting another tag.
- **The app signing key is the project's own `clarifi.jks`**, uploaded to Play App Signing rather
  than letting Google generate one. That is what keeps a side-loaded APK and a Play install
  interchangeable; with two different keys Android treats them as unrelated apps and switching
  channels costs the user their local database. Do not re-enrol with a generated key.
- `versionCode` is `major * 1_000_000 + minor * 1_000 + patch`. Play refuses a code it has seen
  before, **even for a bundle that was rejected or replaced**, so a botched upload needs a new
  version number, not a re-run.
- Play requires the privacy policy at `PRIVACY.md` to stay reachable and accurate. Adding anything
  that leaves the device means updating it and the console's Data safety form.

---

## What NOT to Do

These rules are specific to this codebase - not generic advice.

1. **Do not acquire `XLSX_LOCK` in a function that calls `set_balance()`** - `set_balance()` acquires the lock internally, causing a deadlock. `_add_txn()` handles this by calling `set_balance()` before re-acquiring the lock for the transaction write.

2. **Do not call `round_currency()` with uppercase currency codes** - `'KRW'` will raise `ValueError`. All internal currency keys are lowercase. Use `_currency_id(val)` to normalize first.

3. **Do not read balances from the `config` sheet** - use `get_balances()` which reads from `accounts`. The config keys are a legacy mirror only.

4. **Do not iterate forward when deleting Excel rows** - always iterate `range(ws.max_row, 1, -1)` backwards. Forward deletion shifts indices and skips rows.

5. **Do not use `'income'`, `'debit'`, or any other string for transaction type** - only `'fund'`, `'expense'`, and `'transfer'` are valid. These are compared as literals throughout both backend and frontend. Transfer rows always come in pairs sharing the same `transfer_id`, with `transfer_dir` set to `'out'` on the source leg and `'in'` on the destination leg; the `counterpart` column holds the other leg's account id. A transfer counts as income or spending **for the account it touched** (the per-account In/Out of the last 30 days and that account's monthly chart include it, because from there the money really did arrive or leave), but never in the cross-account totals or the monthly chart of "all accounts", where the two legs are the same money and would inflate both sides at once. It does not belong to a real `category` either (the literal string `'Transfer'` is used as a placeholder), so it stays out of the donut on both views. `build_summary` and `SummaryRepository.build` implement this split the same way and must be changed together. Transfers cannot be edited in place - delete + recreate (deleting either leg deletes both and reverses both balance updates).

6. **Do not import Chart.js or any charting library** - the canvas charts are intentionally handwritten. Adding a library would break the chart rendering code.

7. **Do not create a requirements.txt and add packages to it** - the project has no dependency management file by design. Document any new dependencies in README.md only.

8. **Do not use `_next_id(ws)` for account IDs** - accounts use `_new_account_id(existing_ids)` which generates `'acct_' + token_hex(4)`. `_next_id` only works for integer-ID sheets (transactions, fixed_payments).

9. **Do not call `wb.save()` outside the lock** - save must happen inside `with XLSX_LOCK` to prevent concurrent writes. Always load, mutate, and save within the same `with` block.

10. **Do not use Flask blueprints, separate route files, or a services layer** - the architecture is deliberately monolithic. Adding structure not present in the existing code will create inconsistency.

11. **Do not add server-side validation for amounts against current balance** - the app allows going negative. `_add_txn()` does not check that the account has sufficient balance before subtracting.

12. **Do not hardcode port 5000** - the dev server uses 5000 but `launcher.py` picks a random free port at runtime for the installed exe. Anything that assumes `localhost:5000` will break in the desktop build. Read `request.host_url` server-side, or use relative URLs client-side (already the convention - all `fetch('/api/...')` calls are relative).

13. **Do not write data files next to the executable** - in the frozen build, the app lives in `Program Files\ClariFi\` which is read-only without admin. Always go through `DATA_PATH` (which `_default_data_path()` routes to `%APPDATA%\ClariFi\` when frozen). If you add a new persistent file, follow the same pattern.

14. **A version bump is three files, not one.** `APP_VERSION` in `app.py`, `MyAppVersion` in
    `ClariFi.iss` (on `release`), and `clarifiVersion` in `android/app/build.gradle.kts` must all
    carry the same number. `APP_VERSION` drives the desktop's Updates tab, `MyAppVersion` decides
    the installer's filename and its Add/Remove Programs entry, and `clarifiVersion` is what the
    Android app shows on its About screen and what Play orders releases by. CI passes the tag to
    Gradle, so a stale literal only shows up in local builds - which is exactly the build handed to
    someone for testing, where a wrong version is worst. Grep the new number afterwards; if it does
    not appear in all three, the bump is half done.

    **Every version bump must also create and push a matching `vX.Y.Z` git tag** - without the tag,
    the in-app updater has no GitHub release to discover, so the bump is invisible to users. After
    committing the bump, run `git tag v<new-version> <main-commit>` and
    `git push origin v<new-version>`.

15. **Do not add `Co-Authored-By: Claude` (or similar) trailers to commits in this repo** - the user wants only their own name on the contributors list. Standard git commit messages, no co-author trailer.

16. **Push `release` before `main`, and never push the version tag before both branches are pushed** - when a release touches both branches (typical for a version bump), the order is fixed: (1) commit + push `release`, (2) commit + push `main`, (3) create the `vX.Y.Z` tag on the `main` commit and push the tag, (4) **immediately** run the
    `gh release edit` from rule 17. Step 4 is part of the push, not a follow-up: the workflow
    auto-creates the release the moment the tag lands, titled with the raw tag and bodied with the
    commit message, and it stays wrong in public until the edit runs. Do not start watching the
    build before doing it. The workflow checks out `main` for app source but pulls the build files (`ClariFi.spec`, `ClariFi.iss`, `launcher.py`, `clarifi.ico`, `build-deb.sh`, `run.sh`, `clarifi.desktop`) from `origin/release` - pushing the tag before `release` is up to date means the workflow ships packages with the old `MyAppVersion` and old launcher. (The build-tooling branch was renamed from `build` to `release`; some history still references the old name.)

17. **Do not freestyle the GitHub release title or body** - every GitHub release must follow this exact format. The title is `ClariFi <X.Y.Z>` (no `v` prefix). The body is:

    ```markdown
    ## What's new
    - <user-facing change>
    - <user-facing change>
    - <user-facing change>

    ## Install
    - Windows: download `ClariFi-Setup-<X.Y.Z>.exe` below and run it.
    - Linux (Debian/Ubuntu): download `clarifi_<X.Y.Z>_amd64.deb` below and install it with `sudo apt install ./clarifi_<X.Y.Z>_amd64.deb`.
    ```

    Bullets describe **user-visible** behavior, not internal refactors or version bumps. The asset filenames in the Install section must match the actual asset names (the `.exe` is driven by `MyAppVersion` in `ClariFi.iss`; the `.deb` by the version passed to `build-deb.sh`).

    **The release workflow does NOT set these for you.** `softprops/action-gh-release` auto-creates the release using the tag name (`v0.1.6`) as the title and the commit message as the body - both wrong by this convention. So **every** `git push origin v<X.Y.Z>` must be followed by a `gh release edit v<X.Y.Z> --title "ClariFi <X.Y.Z>" --notes "..."` call to overwrite the auto-generated title and body. Pushing the tag without immediately running `gh release edit` leaves the release in the wrong format. Treat the `gh release edit` step as part of the release push order, not an optional follow-up.

18. **No em dashes (U+2014), anywhere, ever.** Not in UI copy, not in code comments, not in
    `CLAUDE.md`, not in commit messages, not in release notes, not in replies to the user. The same
    goes for en dashes (U+2013) in ranges: write `1-31`. Use a plain hyphen, a comma, parentheses,
    or two sentences. The user has asked for this repeatedly and reads every one of them as a tell
    that a machine wrote the text; a rule that only covers the code misses the place he actually
    notices it, which is the chat. Run `rg -n '[\x{2013}\x{2014}]'` over the repo before shipping a
    release or a doc change. It should return nothing, including from this file.
