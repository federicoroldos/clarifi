# ClariFi

A clean, fast personal finance tracker for Windows. Manage multiple bank accounts in multiple currencies, log income and expenses, automate recurring payments, and see where your money goes, all without sending a single byte to a server you don't own.

ClariFi runs as a native Windows desktop app (Python + Flask + pywebview, packaged as a single installer). Your data lives in a local Excel file under `%APPDATA%\ClariFi\`, so it is easy to back up, audit, or move to another machine.

## Features

### Accounts & currencies
- Unlimited bank accounts, each with its own currency, color, and starting balance
- Five built-in currencies: **USD**, **EUR**, **ARS**, **UYU**, **KRW** (correct decimal rounding per currency)
- Per-account color picker with eight preset swatches plus custom hex
- Archive accounts to hide them from the UI without losing history; permanent delete cascades transactions and fixed payments

### Dashboard
- Clean dark and light themes
- All-account overview plus drilldown for each individual account
- Multi-currency totals grouped by currency
- Handwritten canvas charts: monthly money-flow bars with hover tooltips, spending-by-category donut with hover tooltips, last-30-days summaries

### Transactions
- Add, edit, and delete income (funds) and expenses
- Per-transaction date, amount, description, and category
- Default date pre-filled to today
- Deleting a transaction automatically reverses its balance change
- Categories: Supermarket, Food, Transport, Games, Services, Health, Others
- **Advanced filters**: filter the transaction list by date range and min/max amount, in addition to type and category

### Scan receipts (AI)
- **Scan Receipt** tab: upload or drag-and-drop a photo of any receipt and ClariFi reads it for you
- Accepts JPG, PNG, WEBP and HEIC (the format iPhones shoot by default)
- Requires an AI API key (Settings tab). The photo is sent to your chosen provider's vision model, which reads the image directly for the best accuracy, even on faded, creased or angled receipts
- ClariFi auto-detects the provider from the key and supports [Groq](https://console.groq.com/keys) and [Google Gemini](https://aistudio.google.com/app/apikey) (both have free tiers) or [Claude](https://console.anthropic.com/settings/keys) (paid)
- Auto-detects amount (the grand total), date, merchant, category, currency, and whether it's an expense or a refund/credit
- Always shows an editable review form prefilled with the detected values; nothing is saved until you confirm
- The API key is stored only on your device and is never included in JSON exports

### Transfers between accounts
- Move money between any two of your accounts in one step
- Each transfer creates a paired out/in entry so both balances stay correct
- Transfers are excluded from spend/income stats and the donut/monthly charts so they don't distort your reports
- Deleting either leg of a transfer removes the other leg and reverses both balance changes

### Fixed payments
- Define recurring monthly payments per account (rent, subscriptions, utilities…)
- Supports both **expenses** (rent, subscriptions) and **income** (paychecks, allowances): one place to track every recurring movement
- Due-this-month detection based on the configured day
- One-click "Apply" creates the transaction for the current month
- "Undo" reverses an applied payment and restores the balance

### Data
- Local Excel database (`finance_data.xlsx`), no SQLite, no cloud
- Full JSON export and import for backups and machine migration
- "Clear all data" with a clean reset to default accounts

### Updates
- Built-in **Updates** tab checks GitHub Releases for new versions
- One-click in-app update: downloads the installer with a live **progress bar**, then closes and relaunches automatically
- Manual override: open the GitHub release page directly

### Desktop integration
- Custom frameless title bar
- Native pywebview window, no browser tab required
- Single-file Windows installer (`ClariFi-Setup-<version>.exe`)
- Per-user install (no admin required); user data preserved across uninstall

## Tech stack

- **Python 3.13** + **Flask 3.1**
- **openpyxl** for the Excel-backed datastore
- **Pillow** for image handling (receipt scanning); **pillow-heif** adds HEIC (iPhone photo) support
- **pypdf** for extracting text and page images from bank-statement PDFs (Import Statement feature)
- An AI provider (Groq / Gemini / Claude) reads receipts and statements; the key is supplied by the user
- **pywebview** for the native window (desktop build only)
- **PyInstaller** + **Inno Setup 6** for the installer
- Pure vanilla JavaScript frontend: no npm, no bundler, no chart library

## Getting ClariFi

There are two ways to use ClariFi. Both run entirely on your machine: no cloud, no account, no telemetry. Pick whichever you're more comfortable with.

### Option 1: Download the installer (easiest)

Grab the latest `ClariFi-Setup-<version>.exe` from the [Releases page](https://github.com/federicoroldos/clarifi/releases) and run it. The app installs per-user under `%LOCALAPPDATA%\Programs\ClariFi\` and keeps your data in `%APPDATA%\ClariFi\finance_data.xlsx`. The built-in **Updates** tab handles future releases for you.

### Option 2: Run from source (if you'd rather inspect the code yourself)

If you don't want to trust a pre-built binary, you can clone this repo and run the exact same app locally. It's the same Python + Flask app the installer wraps, nothing hidden.

Requirements: Python 3.10 or newer.

```bash
git clone https://github.com/federicoroldos/clarifi.git
cd clarifi
pip install flask openpyxl
```

Then on Windows just double-click **`Start.bat`**.

#### AI features: receipt scanning and statement import

**Scan Receipt** and **Import Statement** both use an AI provider, and **require a key** (there is no OCR fallback). ClariFi auto-detects the provider from the key: [Groq](https://console.groq.com/keys) (`gsk_…`), [Google Gemini](https://aistudio.google.com/app/apikey) (`AIza…`) or [Claude](https://console.anthropic.com/settings/keys) (`sk-ant-…`). Paste the key in the Settings tab, or set the `GROQ_API_KEY`, `GEMINI_API_KEY` or `ANTHROPIC_API_KEY` environment variable.

- **Receipts:** the photo is sent straight to the provider's vision model, which reads the image directly. It handles faded, creased or angled receipts far better than OCR.
- **Statements:** text-based PDFs are read locally with `pypdf` and the extracted text is sent to the provider. Scanned/image PDFs have their page images sent to the vision model instead. The **Import Statement** tab lets you review every detected movement in an editable table (likely duplicates are flagged and pre-unchecked) before creating them in one go.

From source, these features need:

```bash
pip install pillow pillow-heif pypdf
```

(`pillow-heif` is optional; it lets ClariFi read HEIC photos straight from an iPhone.)

**Privacy:** the receipt image, the scanned-statement pages, and a text statement's extracted text are sent to your chosen AI provider. Nothing else leaves your machine. If you do not set a key, these two features are simply unavailable; the rest of the app stays fully local.

When running from source, `finance_data.xlsx` lives next to `app.py` instead of in `%APPDATA%`, so your data stays inside the cloned folder.

## Building the installer

See [BUILD.md](BUILD.md) on the `build` branch for the two-stage PyInstaller + Inno Setup pipeline.

## How persistence works

When the app starts, if `finance_data.xlsx` does not exist it is auto-created with these sheets:

- `config`: legacy single-currency balances (kept in sync for back-compat)
- `accounts`: user-defined bank accounts (the source of truth for balances)
- `transactions`: full transaction history
- `fixed_payments`: recurring payment definitions
- `fixed_applied`: which fixed payments have been applied per month

A fresh install seeds two default accounts: USD and EUR. Pre-existing installs that only had legacy KRW/UYU/USD balances are migrated automatically.

## License

MIT. See the repository.
