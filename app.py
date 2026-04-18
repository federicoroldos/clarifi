from flask import Flask, jsonify, request, render_template_string
from openpyxl import Workbook, load_workbook
from openpyxl.styles import Font, PatternFill, Alignment
from datetime import datetime, timedelta
import os

app = Flask(__name__)
DB_FILE = "finance_data.xlsx"

CATEGORIES = ["Supermarket", "Food", "Transport", "Games", "Study", "Other"]
ACCOUNTS = {
    "uyu": {"name": "Pesos", "currency": "UYU", "symbol": "$U"},
    "usd": {"name": "Dollars", "currency": "USD", "symbol": "US$"},
}

def init_db():
    if os.path.exists(DB_FILE):
        return
    wb = Workbook()
    ws = wb.active
    ws.title = "config"
    ws.append(["key", "value"])
    ws.append(["balance_uyu", 0])
    ws.append(["balance_usd", 0])
    ws2 = wb.create_sheet("transactions")
    ws2.append(["id", "date", "description", "amount", "category", "type", "account"])
    header_font = Font(bold=True, color="FFFFFF")
    header_fill = PatternFill("solid", start_color="2D5FA6")
    for sheet in [ws, ws2]:
        for cell in sheet[1]:
            cell.font = header_font
            cell.fill = header_fill
            cell.alignment = Alignment(horizontal="center")
    wb.save(DB_FILE)

def read_sheet(sheet_name):
    wb = load_workbook(DB_FILE)
    ws = wb[sheet_name]
    rows = list(ws.iter_rows(values_only=True))
    if not rows:
        return []
    headers = rows[0]
    return [dict(zip(headers, row)) for row in rows[1:] if any(v is not None for v in row)]

def get_balances():
    rows = read_sheet("config")
    cfg = {r["key"]: r["value"] for r in rows}
    return {"uyu": float(cfg.get("balance_uyu", 0)), "usd": float(cfg.get("balance_usd", 0))}

def set_balance(account, val):
    wb = load_workbook(DB_FILE)
    ws = wb["config"]
    key = f"balance_{account}"
    for row in ws.iter_rows(min_row=2):
        if row[0].value == key:
            row[1].value = round(float(val), 2)
            break
    wb.save(DB_FILE)

def next_id():
    txns = read_sheet("transactions")
    if not txns:
        return 1
    ids = [t["id"] for t in txns if t["id"] is not None]
    return max(ids) + 1 if ids else 1

@app.route("/")
def index():
    return render_template_string(HTML)

@app.route("/api/summary")
def api_summary():
    balances = get_balances()
    txns = read_sheet("transactions")
    stats = {acc: {"expenses_by_cat": {}, "monthly": {}, "last30": 0} for acc in ACCOUNTS}
    cutoff = (datetime.now() - timedelta(days=30)).strftime("%Y-%m-%d")
    for t in txns:
        acc = t.get("account") or "uyu"
        if acc not in stats:
            continue
        amount = t["amount"] or 0
        date_str = str(t["date"]) if t["date"] else ""
        m = date_str[:7]
        if t["type"] == "expense":
            cat = t["category"] or "Other"
            stats[acc]["expenses_by_cat"][cat] = stats[acc]["expenses_by_cat"].get(cat, 0) + amount
            if date_str >= cutoff:
                stats[acc]["last30"] += amount
        if m:
            if m not in stats[acc]["monthly"]:
                stats[acc]["monthly"][m] = {"in": 0, "out": 0}
            if t["type"] == "fund":
                stats[acc]["monthly"][m]["in"] += amount
            else:
                stats[acc]["monthly"][m]["out"] += amount
    for acc in stats:
        months = sorted(stats[acc]["monthly"].keys())[-6:]
        stats[acc]["monthly"] = {m: stats[acc]["monthly"][m] for m in months}
    recent = sorted(txns, key=lambda t: str(t["date"]) if t["date"] else "", reverse=True)[:15]
    return jsonify({"balances": balances, "stats": stats, "recent": recent,
                    "categories": CATEGORIES, "accounts": ACCOUNTS, "total_txns": len(txns)})

@app.route("/api/transactions", methods=["GET"])
def get_transactions():
    return jsonify(read_sheet("transactions"))

@app.route("/api/fund", methods=["POST"])
def add_funds():
    data = request.json
    acc = data.get("account", "uyu")
    amount = float(data.get("amount", 0))
    new_bal = get_balances()[acc] + amount
    set_balance(acc, new_bal)
    wb = load_workbook(DB_FILE)
    ws = wb["transactions"]
    ws.append([next_id(), data.get("date", datetime.now().strftime("%Y-%m-%d")),
               data.get("description", "Funds added"), amount, "—", "fund", acc])
    wb.save(DB_FILE)
    return jsonify({"ok": True, "balance": new_bal, "account": acc})

@app.route("/api/expense", methods=["POST"])
def add_expense():
    data = request.json
    acc = data.get("account", "uyu")
    amount = float(data.get("amount", 0))
    new_bal = get_balances()[acc] - amount
    set_balance(acc, new_bal)
    wb = load_workbook(DB_FILE)
    ws = wb["transactions"]
    ws.append([next_id(), data.get("date", datetime.now().strftime("%Y-%m-%d")),
               data.get("description", ""), amount, data.get("category", "Other"), "expense", acc])
    wb.save(DB_FILE)
    return jsonify({"ok": True, "balance": new_bal, "account": acc})

@app.route("/api/transactions/<int:txn_id>", methods=["DELETE"])
def delete_transaction(txn_id):
    txns = read_sheet("transactions")
    txn = next((t for t in txns if t["id"] == txn_id), None)
    if not txn:
        return jsonify({"ok": False}), 404
    acc = txn.get("account") or "uyu"
    balances = get_balances()
    new_bal = balances[acc] - float(txn["amount"] or 0) if txn["type"] == "fund" else balances[acc] + float(txn["amount"] or 0)
    set_balance(acc, new_bal)
    wb = load_workbook(DB_FILE)
    ws = wb["transactions"]
    for row in ws.iter_rows(min_row=2):
        if row[0].value == txn_id:
            ws.delete_rows(row[0].row)
            break
    wb.save(DB_FILE)
    return jsonify({"ok": True})

@app.route("/api/balance", methods=["POST"])
def set_balance_direct():
    data = request.json
    set_balance(data.get("account", "uyu"), float(data.get("balance", 0)))
    return jsonify({"ok": True})

HTML = """<!DOCTYPE html>
<html lang="en" data-theme="dark" data-lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ClariFy</title>
<link rel="icon" type="image/svg+xml" href="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCA2NCA2NCI+CiAgPGRlZnM+CiAgICA8bGluZWFyR3JhZGllbnQgaWQ9ImJnIiB4MT0iMCUiIHkxPSIwJSIgeDI9IjEwMCUiIHkyPSIxMDAlIj4KICAgICAgPHN0b3Agb2Zmc2V0PSIwJSIgc3RvcC1jb2xvcj0iIzBBODRGRiIvPgogICAgICA8c3RvcCBvZmZzZXQ9IjEwMCUiIHN0b3AtY29sb3I9IiMzMEQxNTgiLz4KICAgIDwvbGluZWFyR3JhZGllbnQ+CiAgICA8bGluZWFyR3JhZGllbnQgaWQ9InNoaW5lIiB4MT0iMCUiIHkxPSIwJSIgeDI9IjAlIiB5Mj0iMTAwJSI+CiAgICAgIDxzdG9wIG9mZnNldD0iMCUiIHN0b3AtY29sb3I9InJnYmEoMjU1LDI1NSwyNTUsMC4xOCkiLz4KICAgICAgPHN0b3Agb2Zmc2V0PSIxMDAlIiBzdG9wLWNvbG9yPSJyZ2JhKDI1NSwyNTUsMjU1LDApIi8+CiAgICA8L2xpbmVhckdyYWRpZW50PgogICAgPGNsaXBQYXRoIGlkPSJyIj48cmVjdCB3aWR0aD0iNjQiIGhlaWdodD0iNjQiIHJ4PSIxNCIvPjwvY2xpcFBhdGg+CiAgPC9kZWZzPgogIDwhLS0gaU9TIHJvdW5kZWQgc3F1YXJlIGJhY2tncm91bmQgLS0+CiAgPHJlY3Qgd2lkdGg9IjY0IiBoZWlnaHQ9IjY0IiByeD0iMTQiIGZpbGw9InVybCgjYmcpIi8+CiAgPCEtLSBpbm5lciBzaGluZSAtLT4KICA8cmVjdCB3aWR0aD0iNjQiIGhlaWdodD0iNjQiIHJ4PSIxNCIgZmlsbD0idXJsKCNzaGluZSkiLz4KICA8IS0tIGNvaW4gY2lyY2xlIC0tPgogIDxjaXJjbGUgY3g9IjMyIiBjeT0iMzIiIHI9IjE4IiBmaWxsPSJyZ2JhKDAsMCwwLDAuMTgpIi8+CiAgPGNpcmNsZSBjeD0iMzIiIGN5PSIzMiIgcj0iMTgiIGZpbGw9Im5vbmUiIHN0cm9rZT0icmdiYSgyNTUsMjU1LDI1NSwwLjM1KSIgc3Ryb2tlLXdpZHRoPSIxLjIiLz4KICA8IS0tIGRvbGxhciBzaWduIC0tPgogIDx0ZXh0IHg9IjMyIiB5PSIzOSIgZm9udC1mYW1pbHk9Ii1hcHBsZS1zeXN0ZW0sU0YgUHJvIERpc3BsYXksSGVsdmV0aWNhIE5ldWUsc2Fucy1zZXJpZiIKICAgICAgICBmb250LXNpemU9IjIyIiBmb250LXdlaWdodD0iNzAwIiBmaWxsPSJ3aGl0ZSIKICAgICAgICB0ZXh0LWFuY2hvcj0ibWlkZGxlIiBsZXR0ZXItc3BhY2luZz0iLTAuNSI+JDwvdGV4dD4KPC9zdmc+">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600&display=swap" rel="stylesheet">
<script src="https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.js"></script>
<style>
/* ── TOKENS ── */
:root {
  --ios-blue:   #0A84FF;
  --ios-green:  #30D158;
  --ios-red:    #FF453A;
  --ios-orange: #FF9F0A;
  --ios-purple: #BF5AF2;
  --ios-teal:   #40CBE0;
  --radius-xs:  8px;
  --radius-sm:  12px;
  --radius-md:  16px;
  --radius-lg:  20px;
  --radius-xl:  28px;
  --spring: cubic-bezier(.4,0,.2,1);
}

[data-theme="dark"] {
  --bg:           #000000;
  --bg2:          #0C0C0E;
  --surface:      rgba(28,28,30,0.72);
  --surface2:     rgba(44,44,46,0.60);
  --surface3:     rgba(58,58,60,0.50);
  --border:       rgba(255,255,255,0.08);
  --border2:      rgba(255,255,255,0.13);
  --label:        #FFFFFF;
  --label2:       rgba(235,235,245,0.60);
  --label3:       rgba(235,235,245,0.30);
  --fill:         rgba(118,118,128,0.24);
  --fill2:        rgba(118,118,128,0.16);
  --orb1: radial-gradient(ellipse 700px 500px at 15% 20%, rgba(10,132,255,0.12) 0%, transparent 70%);
  --orb2: radial-gradient(ellipse 600px 600px at 85% 80%, rgba(48,209,88,0.08) 0%, transparent 70%);
  --orb3: radial-gradient(ellipse 500px 400px at 50% 50%, rgba(191,90,242,0.06) 0%, transparent 70%);
  --chart-grid: rgba(255,255,255,0.05);
  --chart-tick:  rgba(235,235,245,0.40);
  --shadow: 0 2px 20px rgba(0,0,0,0.5), 0 0 0 0.5px rgba(255,255,255,0.06);
  --shadow-sm: 0 1px 8px rgba(0,0,0,0.4);
}

[data-theme="light"] {
  --bg:           #F2F2F7;
  --bg2:          #E5E5EA;
  --surface:      rgba(255,255,255,0.78);
  --surface2:     rgba(255,255,255,0.60);
  --surface3:     rgba(242,242,247,0.80);
  --border:       rgba(60,60,67,0.07);
  --border2:      rgba(60,60,67,0.13);
  --label:        #000000;
  --label2:       rgba(60,60,67,0.60);
  --label3:       rgba(60,60,67,0.30);
  --fill:         rgba(118,118,128,0.12);
  --fill2:        rgba(118,118,128,0.08);
  --orb1: radial-gradient(ellipse 700px 500px at 15% 20%, rgba(10,132,255,0.07) 0%, transparent 70%);
  --orb2: radial-gradient(ellipse 600px 600px at 85% 80%, rgba(48,209,88,0.05) 0%, transparent 70%);
  --orb3: radial-gradient(ellipse 500px 400px at 50% 50%, rgba(191,90,242,0.04) 0%, transparent 70%);
  --chart-grid: rgba(60,60,67,0.06);
  --chart-tick:  rgba(60,60,67,0.45);
  --shadow: 0 2px 16px rgba(0,0,0,0.08), 0 0 0 0.5px rgba(0,0,0,0.05);
  --shadow-sm: 0 1px 6px rgba(0,0,0,0.06);
}

/* ── BASE ── */
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
html { -webkit-text-size-adjust: 100%; }

body {
  font-family: -apple-system, 'SF Pro Text', 'Inter', sans-serif;
  font-size: 15px;
  line-height: 1.45;
  color: var(--label);
  background: var(--bg);
  min-height: 100vh;
  transition: background 0.3s var(--spring), color 0.3s var(--spring);
  -webkit-font-smoothing: antialiased;
}

/* ── BG LAYER ── */
.bg-scene {
  position: fixed; inset: 0; z-index: 0; pointer-events: none;
  background: var(--bg);
  transition: background 0.4s var(--spring);
}
.bg-scene::before  { content:''; position:absolute; inset:0; background: var(--orb1); }
.bg-scene::after   { content:''; position:absolute; inset:0; background: var(--orb2); }
.bg-orb3           { position:absolute; inset:0; background: var(--orb3); }

#app { position: relative; z-index: 1; }

/* ── GLASS SURFACE ── */
.surface {
  background: var(--surface);
  backdrop-filter: blur(32px) saturate(1.8);
  -webkit-backdrop-filter: blur(32px) saturate(1.8);
  border: 0.5px solid var(--border2);
  box-shadow: var(--shadow);
}
.surface-soft {
  background: var(--surface2);
  backdrop-filter: blur(20px) saturate(1.5);
  -webkit-backdrop-filter: blur(20px) saturate(1.5);
  border: 0.5px solid var(--border);
  box-shadow: var(--shadow-sm);
}

/* ── NAV ── */
nav {
  position: sticky; top: 0; z-index: 200;
  padding: 0 20px;
  height: 56px;
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  background: var(--surface);
  backdrop-filter: blur(40px) saturate(2);
  -webkit-backdrop-filter: blur(40px) saturate(2);
  border-bottom: 0.5px solid var(--border2);
  transition: background 0.3s;
}

.nav-brand {
  font-size: 17px;
  font-weight: 600;
  color: var(--label);
  letter-spacing: -0.3px;
  display: flex; align-items: center; gap: 7px;
}
.nav-brand .dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: linear-gradient(135deg, var(--ios-blue), var(--ios-teal));
  flex-shrink: 0;
}

.nav-right { display: flex; align-items: center; gap: 8px; }

/* balance chips */
.nav-bal {
  display: flex; flex-direction: column; align-items: flex-end;
  padding: 6px 11px;
  border-radius: var(--radius-sm);
  background: var(--fill);
  cursor: pointer;
  transition: background 0.2s, transform 0.15s;
  border: none;
  gap: 1px;
}
.nav-bal:hover  { background: var(--fill2); transform: scale(0.97); }
.nav-bal:active { transform: scale(0.94); }
.nav-bal-label { font-size: 10px; font-weight: 500; color: var(--label3); letter-spacing: 0.04em; text-transform: uppercase; }
.nav-bal-val   { font-size: 13px; font-weight: 600; letter-spacing: -0.2px; line-height: 1; }
.nav-bal.uyu .nav-bal-val { color: var(--ios-blue); }
.nav-bal.usd .nav-bal-val { color: var(--ios-green); }

/* icon buttons */
.icon-btn {
  width: 32px; height: 32px;
  border-radius: 50%;
  border: none;
  background: var(--fill);
  color: var(--label2);
  font-size: 14px;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: background 0.2s, transform 0.15s;
  flex-shrink: 0;
}
.icon-btn:hover  { background: var(--fill2); }
.icon-btn:active { transform: scale(0.9); }
.lang-pill {
  width: auto; padding: 0 10px;
  border-radius: var(--radius-xs);
  font-size: 11px; font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--ios-blue);
  background: rgba(10,132,255,0.12);
  border: 0.5px solid rgba(10,132,255,0.22);
}

/* ── SEGMENTED CONTROL (iOS tabs) ── */
.seg-wrap {
  padding: 8px 20px 0;
  background: var(--surface);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-bottom: 0.5px solid var(--border);
}
.seg {
  display: inline-flex;
  background: var(--fill);
  border-radius: var(--radius-sm);
  padding: 2px;
  gap: 2px;
  margin-bottom: 8px;
}
.seg-item {
  padding: 6px 14px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 500;
  color: var(--label2);
  cursor: pointer;
  border: none;
  background: transparent;
  transition: all 0.2s var(--spring);
  white-space: nowrap;
  font-family: inherit;
}
.seg-item:active { transform: scale(0.96); }
.seg-item.active {
  background: var(--surface);
  color: var(--label);
  font-weight: 600;
  box-shadow: 0 1px 4px rgba(0,0,0,0.18), 0 0 0 0.5px var(--border2);
}

/* ── PAGE ── */
.page { display: none; padding: 20px; max-width: 1040px; margin: 0 auto; }
.page.active { display: block; animation: slideUp 0.28s var(--spring); }
@keyframes slideUp { from { opacity:0; transform:translateY(8px); } to { opacity:1; transform:translateY(0); } }

/* ── ACCOUNT PILL TOGGLE ── */
.acc-toggle { display: flex; gap: 6px; margin-bottom: 18px; }
.acc-pill {
  padding: 7px 16px;
  border-radius: var(--radius-xl);
  font-size: 13px; font-weight: 600;
  cursor: pointer; border: none;
  background: var(--fill); color: var(--label2);
  transition: all 0.2s var(--spring);
  font-family: inherit;
}
.acc-pill:active { transform: scale(0.96); }
.acc-pill.uyu { background: rgba(10,132,255,0.15); color: var(--ios-blue); }
.acc-pill.usd { background: rgba(48,209,88,0.13);  color: var(--ios-green); }

/* ── METRIC CARDS ── */
.metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(175px, 1fr)); gap: 10px; margin-bottom: 16px; }
.metric {
  padding: 18px 18px 16px;
  border-radius: var(--radius-lg);
  position: relative;
  overflow: hidden;
  transition: transform 0.2s var(--spring);
}
.metric:active { transform: scale(0.98); }
.metric-eyebrow {
  font-size: 11px; font-weight: 500;
  color: var(--label3);
  text-transform: uppercase; letter-spacing: 0.06em;
  margin-bottom: 6px;
}
.metric-amount {
  font-size: 24px; font-weight: 300;
  letter-spacing: -0.8px;
  line-height: 1.1;
  color: var(--label);
}
.metric-amount .sym {
  font-size: 14px; font-weight: 500;
  letter-spacing: 0;
  vertical-align: super;
  margin-right: 2px;
  opacity: 0.8;
}
.metric-amount .cents {
  font-size: 14px; font-weight: 400;
  opacity: 0.7;
}
.metric-amount.val-uyu  { color: var(--ios-blue); }
.metric-amount.val-usd  { color: var(--ios-green); }
.metric-amount.val-neg  { color: var(--ios-red); }
.metric-amount.val-neutral { color: var(--label); }
.metric-sub { font-size: 12px; color: var(--label3); margin-top: 5px; }

/* ── CARDS ── */
.card { border-radius: var(--radius-lg); padding: 16px; }
.card + .card { margin-top: 10px; }
.card-header { font-size: 11px; font-weight: 600; color: var(--label3); text-transform: uppercase; letter-spacing: 0.07em; margin-bottom: 14px; }
.charts-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 10px; }
.charts-row .card + .card { margin-top: 0; }

/* ── TABLE ── */
.table-wrap { overflow-x: auto; margin: 0 -4px; padding: 0 4px; }
table { width: 100%; border-collapse: collapse; }
th {
  font-size: 11px; font-weight: 500; color: var(--label3);
  text-align: left; padding: 6px 10px;
  text-transform: uppercase; letter-spacing: 0.06em;
  border-bottom: 0.5px solid var(--border2);
}
td {
  padding: 10px 10px;
  font-size: 13px; color: var(--label);
  border-bottom: 0.5px solid var(--border);
  vertical-align: middle;
}
tr:last-child td { border-bottom: none; }
tr:hover td { background: var(--fill2); }
.td-mono { font-size: 14px; font-weight: 500; letter-spacing: -0.3px; }

/* ── CHIPS / BADGES ── */
.chip { display: inline-flex; align-items: center; padding: 3px 9px; border-radius: 99px; font-size: 12px; font-weight: 600; }
.chip-expense { background: rgba(255,69,58,0.12);  color: var(--ios-red);   }
.chip-fund    { background: rgba(48,209,88,0.12);  color: var(--ios-green); }
.chip-uyu     { background: rgba(10,132,255,0.12); color: var(--ios-blue);  }
.chip-usd     { background: rgba(48,209,88,0.12);  color: var(--ios-green); }

/* ── FORMS ── */
.form-stack { display: flex; flex-direction: column; gap: 12px; }
.form-row   { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.field { display: flex; flex-direction: column; gap: 5px; }
.field label { font-size: 12px; font-weight: 500; color: var(--label2); }
input, select {
  border: none;
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  font-size: 15px; font-weight: 400;
  width: 100%;
  outline: none;
  background: var(--fill);
  color: var(--label);
  font-family: inherit;
  transition: background 0.2s, box-shadow 0.2s;
  -webkit-appearance: none;
  appearance: none;
}
input:focus, select:focus {
  background: var(--fill2);
  box-shadow: 0 0 0 3px rgba(10,132,255,0.28);
}
input::placeholder { color: var(--label3); }
select option { background: #1C1C1E; color: #fff; }
[data-theme="light"] select option { background: #fff; color: #000; }

/* amount field */
.amount-field { position: relative; }
.amount-sym {
  position: absolute; left: 12px; top: 50%; transform: translateY(-50%);
  font-size: 13px; font-weight: 600; color: var(--label3); pointer-events: none;
}
.amount-field input { padding-left: 36px; font-size: 17px; font-weight: 300; letter-spacing: -0.4px; }

/* ── TYPE TOGGLE ── */
.type-toggle { display: grid; grid-template-columns: 1fr 1fr; background: var(--fill); border-radius: var(--radius-sm); padding: 3px; gap: 3px; margin-bottom: 16px; }
.type-btn {
  padding: 9px; border-radius: 10px; text-align: center;
  font-size: 14px; font-weight: 600; cursor: pointer; border: none;
  background: transparent; color: var(--label2);
  transition: all 0.2s var(--spring); font-family: inherit;
}
.type-btn.active-expense { background: var(--surface); color: var(--ios-red);   box-shadow: 0 1px 4px rgba(0,0,0,0.18); }
.type-btn.active-fund    { background: var(--surface); color: var(--ios-green); box-shadow: 0 1px 4px rgba(0,0,0,0.18); }

/* ── BUTTONS ── */
.btn {
  display: block; width: 100%;
  padding: 14px;
  border-radius: var(--radius-md);
  font-size: 15px; font-weight: 600;
  cursor: pointer; border: none;
  font-family: inherit;
  transition: all 0.2s var(--spring);
  letter-spacing: -0.1px;
}
.btn:active { transform: scale(0.98); }
.btn-blue  { background: var(--ios-blue);  color: #fff; box-shadow: 0 4px 16px rgba(10,132,255,0.35); }
.btn-green { background: var(--ios-green); color: #fff; box-shadow: 0 4px 16px rgba(48,209,88,0.30); }
.btn-blue:hover  { filter: brightness(1.06); }
.btn-green:hover { filter: brightness(1.06); }
.btn-sm {
  width: auto; display: inline-block; padding: 8px 16px;
  font-size: 14px; border-radius: var(--radius-sm);
}
.del-btn {
  width: 26px; height: 26px; border-radius: 50%;
  background: rgba(255,69,58,0.12); color: var(--ios-red);
  border: none; cursor: pointer; font-size: 13px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s; flex-shrink: 0;
}
.del-btn:hover { background: rgba(255,69,58,0.22); transform: scale(1.1); }

/* ── MSG ── */
.msg { font-size: 13px; font-weight: 500; min-height: 18px; margin-top: 10px; }
.msg.ok  { color: var(--ios-green); }
.msg.err { color: var(--ios-red); }

/* ── FILTER ROW ── */
.filter-row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; }
.filter-controls { display: flex; gap: 8px; }
.filter-controls select, .filter-controls input { width: auto; font-size: 13px; padding: 7px 10px; border-radius: var(--radius-xs); }

/* ── SETTINGS ── */
.settings-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; align-items: stretch; }
.settings-pair .card { height: 100%; }
.settings-pair .card + .card { margin-top: 0; }
.settings-row { display: flex; gap: 8px; align-items: center; margin-top: 10px; }
.settings-row input { max-width: 150px; }

/* ── DIVIDER ── */
.divider { height: 0.5px; background: var(--border); margin: 4px 0; }

/* ── SPACING UTILS ── */
.mt8  { margin-top: 8px; }
.mt16 { margin-top: 16px; }
.section-label { font-size: 13px; font-weight: 600; color: var(--label2); margin-bottom: 10px; }
.label3 { color: var(--label3); }

/* ── RESPONSIVE ── */
@media (max-width: 620px) {
  .charts-row, .form-row, .settings-pair { grid-template-columns: 1fr; }
  nav { padding: 0 14px; }
  .page { padding: 16px 14px; }
  .seg-wrap { padding: 8px 14px 0; }
  .nav-right { gap: 6px; }
}

/* ── SCROLLBAR ── */
::-webkit-scrollbar { width: 4px; height: 4px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: var(--border2); border-radius: 99px; }
</style>
</head>
<body>
<div class="bg-scene"><div class="bg-orb3"></div></div>
<div id="app">

<!-- NAV -->
<nav>
  <div class="nav-brand">
    <span class="dot"></span>
    ClariFy
  </div>
  <div class="nav-right">
    <div class="nav-bal uyu" onclick="showTab('overview');setAccTab('uyu')">
      <span class="nav-bal-label">Pesos</span>
      <span class="nav-bal-val" id="nav-uyu">—</span>
    </div>
    <div class="nav-bal usd" onclick="showTab('overview');setAccTab('usd')">
      <span class="nav-bal-label">Dollars</span>
      <span class="nav-bal-val" id="nav-usd">—</span>
    </div>
    <button class="icon-btn lang-pill" onclick="toggleLang()" id="lang-toggle">EN</button>
    <button class="icon-btn" onclick="toggleTheme()" id="theme-toggle">🌙</button>
  </div>
</nav>

<!-- SEGMENTED TABS -->
<div class="seg-wrap surface">
  <div class="seg">
    <button class="seg-item active" onclick="showTab('overview')" data-i18n="tab_overview">Overview</button>
    <button class="seg-item" onclick="showTab('add')" data-i18n="tab_add">Add</button>
    <button class="seg-item" onclick="showTab('transactions')" data-i18n="tab_history">History</button>
    <button class="seg-item" onclick="showTab('settings')" data-i18n="tab_settings">Settings</button>
  </div>
</div>

<!-- ── OVERVIEW ── -->
<div class="page active" id="page-overview">
  <div class="acc-toggle">
    <button class="acc-pill uyu" id="acctab-uyu" onclick="setAccTab('uyu')">$U Pesos</button>
    <button class="acc-pill" id="acctab-usd" onclick="setAccTab('usd')">US$ Dollars</button>
  </div>
  <div class="metrics" id="metrics"></div>
  <div class="charts-row">
    <div class="card surface">
      <div class="card-header" id="bar-title" data-i18n="chart_flow">Money Flow</div>
      <div style="position:relative;height:190px"><canvas id="barChart"></canvas></div>
    </div>
    <div class="card surface">
      <div class="card-header" data-i18n="chart_categories">By Category</div>
      <div style="position:relative;height:190px"><canvas id="pieChart"></canvas></div>
    </div>
  </div>
  <div class="card surface">
    <div class="card-header" data-i18n="recent_txns">Recent</div>
    <div class="table-wrap">
      <table>
        <thead><tr>
          <th data-i18n="col_date">Date</th>
          <th data-i18n="col_desc">Description</th>
          <th data-i18n="col_cat">Category</th>
          <th data-i18n="col_account">Account</th>
          <th data-i18n="col_type">Type</th>
          <th data-i18n="col_amount">Amount</th>
          <th></th>
        </tr></thead>
        <tbody id="recent-table"></tbody>
      </table>
    </div>
  </div>
</div>

<!-- ── ADD ── -->
<div class="page" id="page-add">
  <div class="card surface" style="max-width:480px;margin:0 auto">
    <div class="type-toggle">
      <button class="type-btn active-expense" id="btn-expense" onclick="setType('expense')" data-i18n="btn_expense">Expense</button>
      <button class="type-btn" id="btn-fund" onclick="setType('fund')" data-i18n="btn_add_funds">Add funds</button>
    </div>
    <div class="form-stack">
      <div class="form-row">
        <div class="field">
          <label data-i18n="lbl_account">Account</label>
          <select id="new-account" onchange="updateCurrencyHint()">
            <option value="uyu" data-i18n="acc_pesos">Pesos (UYU)</option>
            <option value="usd" data-i18n="acc_dollars">Dollars (USD)</option>
          </select>
        </div>
        <div class="field">
          <label data-i18n="lbl_amount">Amount</label>
          <div class="amount-field">
            <span class="amount-sym" id="currency-hint">$U</span>
            <input type="number" id="new-amount" placeholder="0.00" step="0.01" min="0">
          </div>
        </div>
      </div>
      <div class="form-row">
        <div class="field">
          <label data-i18n="lbl_date">Date</label>
          <input type="date" id="new-date">
        </div>
        <div class="field" id="cat-group">
          <label data-i18n="lbl_category">Category</label>
          <select id="new-cat"></select>
        </div>
      </div>
      <div class="field">
        <label data-i18n="lbl_description">Description</label>
        <input type="text" id="new-desc" placeholder="e.g. Supermarket" data-i18n-placeholder="placeholder_desc">
      </div>
    </div>
    <div class="mt16">
      <button class="btn btn-blue" id="submit-btn" onclick="submitEntry()" data-i18n="btn_save_expense">Save expense</button>
    </div>
    <p class="msg" id="add-msg"></p>
  </div>
</div>

<!-- ── HISTORY ── -->
<div class="page" id="page-transactions">
  <div class="card surface">
    <div class="filter-row">
      <span class="section-label" data-i18n="tab_history">History</span>
      <div class="filter-controls">
        <select id="filter-account" onchange="loadTransactions()">
          <option value="" data-i18n="filter_all_accounts">All</option>
          <option value="uyu" data-i18n="acc_pesos">Pesos</option>
          <option value="usd" data-i18n="acc_dollars">Dollars</option>
        </select>
        <input type="month" id="filter-month" onchange="loadTransactions()">
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr>
          <th data-i18n="col_date">Date</th>
          <th data-i18n="col_desc">Description</th>
          <th data-i18n="col_cat">Category</th>
          <th data-i18n="col_account">Account</th>
          <th data-i18n="col_type">Type</th>
          <th data-i18n="col_amount">Amount</th>
          <th></th>
        </tr></thead>
        <tbody id="txn-table"></tbody>
      </table>
    </div>
  </div>
</div>

<!-- ── SETTINGS ── -->
<div class="page" id="page-settings">
  <p style="font-size:13px;color:var(--label3);margin-bottom:16px" data-i18n="settings_desc">Sync with your actual bank balances. Won't create a transaction.</p>
  <div class="settings-pair">
    <div class="card surface">
      <div class="card-header" style="color:var(--ios-blue)">$U Pesos · UYU</div>
      <div class="settings-row">
        <input type="number" id="bal-uyu" placeholder="0.00" step="0.01">
        <button class="btn btn-blue btn-sm" onclick="saveBalance('uyu')" data-i18n="btn_save">Save</button>
      </div>
      <p class="msg" id="msg-uyu"></p>
    </div>
    <div class="card surface">
      <div class="card-header" style="color:var(--ios-green)">US$ Dollars · USD</div>
      <div class="settings-row">
        <input type="number" id="bal-usd" placeholder="0.00" step="0.01">
        <button class="btn btn-green btn-sm" onclick="saveBalance('usd')" data-i18n="btn_save">Save</button>
      </div>
      <p class="msg" id="msg-usd"></p>
    </div>
  </div>
</div>

</div><!-- #app -->

<script>
/* ── I18N ── */
const STRINGS = {
  en: {
    tab_overview:"Overview", tab_add:"Add", tab_history:"History", tab_settings:"Settings",
    acc_pesos:"$U Pesos", acc_dollars:"US$ Dollars",
    chart_flow:"Money Flow", chart_categories:"By Category",
    recent_txns:"Recent", col_date:"Date", col_desc:"Description", col_cat:"Category",
    col_account:"Account", col_type:"Type", col_amount:"Amount",
    btn_expense:"Expense", btn_add_funds:"Add funds",
    lbl_account:"Account", lbl_amount:"Amount", lbl_date:"Date",
    lbl_category:"Category", lbl_description:"Description",
    placeholder_desc:"e.g. Supermarket",
    btn_save_expense:"Save expense", btn_save_funds:"Add funds", btn_save:"Save",
    filter_all_accounts:"All accounts",
    settings_desc:"Sync with your actual bank balances. Won't create a transaction.",
    metric_balance:"Balance", metric_spent:"Spent · 30 days", metric_txns:"Transactions",
    metric_all:"all accounts", metric_current:"current balance",
    saved_ok:"Saved · new balance:", balance_updated:"Updated to",
    fill_fields:"Please fill in amount and description.",
    delete_confirm:"Delete this transaction? Balance will be reversed.",
    no_txns:"No transactions yet", no_txns_period:"Nothing this period",
    funds_label:"funds", expense_label:"expense",
    pesos_label:"$U Pesos", dollars_label:"US$ Dollars",
  },
  es: {
    tab_overview:"Resumen", tab_add:"Agregar", tab_history:"Historial", tab_settings:"Ajustes",
    acc_pesos:"$U Pesos", acc_dollars:"US$ Dólares",
    chart_flow:"Flujo", chart_categories:"Por categoría",
    recent_txns:"Recientes", col_date:"Fecha", col_desc:"Descripción", col_cat:"Categoría",
    col_account:"Cuenta", col_type:"Tipo", col_amount:"Monto",
    btn_expense:"Gasto", btn_add_funds:"Agregar fondos",
    lbl_account:"Cuenta", lbl_amount:"Monto", lbl_date:"Fecha",
    lbl_category:"Categoría", lbl_description:"Descripción",
    placeholder_desc:"ej. Supermercado",
    btn_save_expense:"Guardar gasto", btn_save_funds:"Agregar fondos", btn_save:"Guardar",
    filter_all_accounts:"Todas las cuentas",
    settings_desc:"Sincronizá con tu saldo real. No crea una transacción.",
    metric_balance:"Saldo", metric_spent:"Gastado · 30 días", metric_txns:"Transacciones",
    metric_all:"todas las cuentas", metric_current:"saldo actual",
    saved_ok:"Guardado · nuevo saldo:", balance_updated:"Actualizado a",
    fill_fields:"Por favor completá el monto y la descripción.",
    delete_confirm:"¿Eliminar esta transacción? El saldo será revertido.",
    no_txns:"Sin transacciones aún", no_txns_period:"Nada en este período",
    funds_label:"fondos", expense_label:"gasto",
    pesos_label:"$U Pesos", dollars_label:"US$ Dólares",
  }
};

let lang = localStorage.getItem('fin_lang') || 'en';
function t(k) { return (STRINGS[lang]||STRINGS.en)[k] || k; }

function applyLang() {
  document.documentElement.lang = lang;
  document.getElementById('lang-toggle').textContent = lang==='en' ? 'ES' : 'EN';
  document.querySelectorAll('[data-i18n]').forEach(el => { el.textContent = t(el.dataset.i18n); });
  document.querySelectorAll('[data-i18n-placeholder]').forEach(el => { el.placeholder = t(el.dataset.i18nPlaceholder); });
  if (summary.balances) { renderMetrics(); renderRecent(); }
  if (document.getElementById('page-transactions').classList.contains('active')) loadTransactions();
  // update type button
  setType(currentType);
}
function toggleLang() { lang = lang==='en'?'es':'en'; localStorage.setItem('fin_lang',lang); applyLang(); }

/* ── THEME ── */
let theme = localStorage.getItem('fin_theme') || 'dark';
function applyTheme() {
  document.documentElement.setAttribute('data-theme', theme);
  document.getElementById('theme-toggle').textContent = theme==='dark' ? '☀️' : '🌙';
  if (summary.stats) renderCharts();
}
function toggleTheme() { theme = theme==='dark'?'light':'dark'; localStorage.setItem('fin_theme',theme); applyTheme(); }

/* ── CHARTS ── */
let summary={}, barChart, pieChart, currentType='expense', currentAcc='uyu';
const PAL=['#0A84FF','#FF9F0A','#30D158','#FF375F','#BF5AF2','#64D2FF','#FFD60A'];
const SYM={uyu:'$U',usd:'US$'};

function fmtAmount(n, acc) {
  // returns {sym, int, dec}
  const abs = Math.abs(Number(n));
  const parts = abs.toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:2}).split('.');
  return { sym: acc?SYM[acc]:'', int: parts[0], dec: '.'+parts[1], neg: Number(n)<0 };
}

function fmtSimple(n, acc) {
  const f = fmtAmount(n,acc);
  return (f.neg?'- ':'')+f.sym+' '+f.int+f.dec;
}

function metricHTML(n, acc) {
  const f = fmtAmount(n, acc);
  const cls = n<0 ? 'val-neg' : acc==='uyu' ? 'val-uyu' : acc==='usd' ? 'val-usd' : 'val-neutral';
  return `<div class="metric-amount ${cls}">${f.neg?'<span style="opacity:.6">−</span>':''}<span class="sym">${f.sym}</span>${f.int}<span class="cents">${f.dec}</span></div>`;
}

function showTab(name) {
  document.querySelectorAll('.seg-item').forEach(el=>el.classList.remove('active'));
  document.querySelectorAll('.page').forEach(el=>el.classList.remove('active'));
  const idx=['overview','add','transactions','settings'].indexOf(name);
  document.querySelectorAll('.seg-item')[idx].classList.add('active');
  document.getElementById('page-'+name).classList.add('active');
  if(name==='transactions') loadTransactions();
  if(name==='settings'){
    const b=summary.balances||{};
    document.getElementById('bal-uyu').value=(b.uyu||0).toFixed(2);
    document.getElementById('bal-usd').value=(b.usd||0).toFixed(2);
  }
}

function setAccTab(acc) {
  currentAcc=acc;
  ['uyu','usd'].forEach(a=>{
    const el=document.getElementById('acctab-'+a);
    el.className='acc-pill'+(a===acc?' '+a:'');
  });
  renderMetrics(); renderCharts();
}

function setType(type) {
  currentType=type;
  document.getElementById('btn-expense').className='type-btn'+(type==='expense'?' active-expense':'');
  document.getElementById('btn-fund').className='type-btn'+(type==='fund'?' active-fund':'');
  document.getElementById('cat-group').style.display=type==='expense'?'':'none';
  document.getElementById('submit-btn').textContent=type==='expense'?t('btn_save_expense'):t('btn_save_funds');
  document.getElementById('submit-btn').className='btn btn-'+(type==='expense'?'blue':'green');
}

function updateCurrencyHint() {
  document.getElementById('currency-hint').textContent=SYM[document.getElementById('new-account').value];
}

function chartColors() {
  return {
    grid: theme==='dark'?'rgba(255,255,255,0.05)':'rgba(60,60,67,0.06)',
    tick: theme==='dark'?'rgba(235,235,245,0.40)':'rgba(60,60,67,0.45)',
  };
}

async function loadSummary() {
  const r=await fetch('/api/summary'); summary=await r.json();
  const b=summary.balances||{};
  document.getElementById('nav-uyu').textContent=fmtSimple(b.uyu||0,'uyu');
  document.getElementById('nav-usd').textContent=fmtSimple(b.usd||0,'usd');
  document.getElementById('new-cat').innerHTML=(summary.categories||[]).map(c=>`<option>${c}</option>`).join('');
  renderMetrics(); renderCharts(); renderRecent();
}

function renderMetrics() {
  const b=(summary.balances||{})[currentAcc]||0;
  const st=(summary.stats||{})[currentAcc]||{};
  document.getElementById('metrics').innerHTML=`
    <div class="metric surface">
      <div class="metric-eyebrow">${t('metric_balance')}</div>
      ${metricHTML(b,currentAcc)}
      <div class="metric-sub">${t('metric_current')}</div>
    </div>
    <div class="metric surface">
      <div class="metric-eyebrow">${t('metric_spent')}</div>
      ${metricHTML(st.last30||0,currentAcc)}
    </div>
    <div class="metric surface">
      <div class="metric-eyebrow">${t('metric_txns')}</div>
      <div class="metric-amount val-neutral" style="font-weight:300">${summary.total_txns||0}</div>
      <div class="metric-sub">${t('metric_all')}</div>
    </div>
  `;
}

function renderCharts() {
  const st=(summary.stats||{})[currentAcc]||{};
  const monthly=st.monthly||{};
  const months=Object.keys(monthly);
  const cc=chartColors();
  const acIn=currentAcc==='uyu'?'#0A84FF':'#30D158';
  const acInFill=currentAcc==='uyu'?'rgba(10,132,255,0.55)':'rgba(48,209,88,0.55)';

  if(barChart) barChart.destroy();
  if(months.length){
    const labels=months.map(m=>{const[y,mo]=m.split('-');return new Date(y,mo-1).toLocaleString('default',{month:'short'});});
    barChart=new Chart(document.getElementById('barChart'),{
      type:'bar',
      data:{labels,datasets:[
        {label:lang==='en'?'In':'Entrada', data:months.map(m=>monthly[m].in),  backgroundColor:acInFill, borderColor:acIn, borderWidth:1, borderRadius:6},
        {label:lang==='en'?'Out':'Salida', data:months.map(m=>monthly[m].out), backgroundColor:'rgba(255,69,58,0.45)', borderColor:'#FF453A', borderWidth:1, borderRadius:6}
      ]},
      options:{responsive:true,maintainAspectRatio:false,
        plugins:{legend:{labels:{font:{size:11,family:'-apple-system'},color:cc.tick,boxWidth:9,padding:14}}},
        scales:{
          x:{grid:{display:false},ticks:{font:{size:11},color:cc.tick}},
          y:{grid:{color:cc.grid},border:{dash:[4,4]},ticks:{font:{size:11},color:cc.tick,callback:v=>SYM[currentAcc]+' '+v.toLocaleString()}}
        }
      }
    });
  }

  const expCat=st.expenses_by_cat||{};
  const cats=Object.keys(expCat);
  if(pieChart) pieChart.destroy();
  if(cats.length){
    pieChart=new Chart(document.getElementById('pieChart'),{
      type:'doughnut',
      data:{labels:cats,datasets:[{data:cats.map(c=>expCat[c]),backgroundColor:PAL,borderWidth:0,hoverOffset:6}]},
      options:{responsive:true,maintainAspectRatio:false,cutout:'66%',
        plugins:{legend:{position:'right',labels:{font:{size:11,family:'-apple-system'},color:cc.tick,boxWidth:9,padding:10}}}
      }
    });
  }
}

function txnRow(txn) {
  const isFund=txn.type==='fund';
  const acc=txn.account||'uyu';
  const color=isFund?'var(--ios-green)':'var(--ios-red)';
  return `<tr>
    <td style="color:var(--label2);font-size:12px">${txn.date||''}</td>
    <td style="font-weight:500">${txn.description||''}</td>
    <td style="color:var(--label2)">${txn.category||''}</td>
    <td><span class="chip chip-${acc}">${acc==='uyu'?t('pesos_label'):t('dollars_label')}</span></td>
    <td><span class="chip chip-${txn.type}">${isFund?t('funds_label'):t('expense_label')}</span></td>
    <td class="td-mono" style="color:${color}">${isFund?'+':'−'} ${fmtSimple(txn.amount,acc)}</td>
    <td><button class="del-btn" onclick="deleteTxn(${txn.id})">×</button></td>
  </tr>`;
}

function renderRecent() {
  const rows=(summary.recent||[]).map(txnRow).join('');
  document.getElementById('recent-table').innerHTML=rows||`<tr><td colspan="7" style="color:var(--label3);text-align:center;padding:28px;font-size:13px">${t('no_txns')}</td></tr>`;
}

async function loadTransactions() {
  const fm=document.getElementById('filter-month').value;
  const fa=document.getElementById('filter-account').value;
  const r=await fetch('/api/transactions');
  let txns=await r.json();
  if(fm) txns=txns.filter(t=>t.date&&String(t.date).startsWith(fm));
  if(fa) txns=txns.filter(t=>(t.account||'uyu')===fa);
  txns.sort((a,b)=>String(b.date).localeCompare(String(a.date)));
  document.getElementById('txn-table').innerHTML=txns.map(txnRow).join('')||`<tr><td colspan="7" style="color:var(--label3);text-align:center;padding:28px;font-size:13px">${t('no_txns_period')}</td></tr>`;
}

async function submitEntry() {
  const amount=document.getElementById('new-amount').value;
  const description=document.getElementById('new-desc').value;
  const account=document.getElementById('new-account').value;
  const msg=document.getElementById('add-msg');
  if(!amount||!description){msg.textContent=t('fill_fields');msg.className='msg err';return;}
  const payload={amount,description,account,date:document.getElementById('new-date').value,category:document.getElementById('new-cat').value};
  const r=await fetch(currentType==='fund'?'/api/fund':'/api/expense',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
  const data=await r.json();
  msg.textContent=t('saved_ok')+' '+fmtSimple(data.balance,account);
  msg.className='msg ok';
  document.getElementById('new-amount').value='';
  document.getElementById('new-desc').value='';
  loadSummary();
  setTimeout(()=>{msg.textContent='';},4000);
}

async function deleteTxn(id) {
  if(!confirm(t('delete_confirm'))) return;
  await fetch('/api/transactions/'+id,{method:'DELETE'});
  loadSummary();
  if(document.getElementById('page-transactions').classList.contains('active')) loadTransactions();
}

async function saveBalance(acc) {
  const val=document.getElementById('bal-'+acc).value;
  const msg=document.getElementById('msg-'+acc);
  if(!val) return;
  await fetch('/api/balance',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({account:acc,balance:val})});
  msg.textContent=t('balance_updated')+' '+fmtSimple(val,acc);
  msg.className='msg ok';
  loadSummary();
  setTimeout(()=>{msg.textContent='';},4000);
}

/* ── INIT ── */
const today=new Date().toISOString().split('T')[0];
document.getElementById('new-date').value=today;
document.getElementById('filter-month').value=today.slice(0,7);
setType('expense');
applyTheme();
applyLang();
loadSummary();
</script>
</body>
</html>
PYEOF"""

if __name__ == "__main__":
    init_db()
    print("\n  ClariFy Dashboard → http://localhost:5000\n")
    app.run(debug=False, port=5000)
