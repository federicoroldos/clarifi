from flask import Flask, jsonify, request, render_template_string
from openpyxl import Workbook, load_workbook
from openpyxl.styles import Font, PatternFill, Alignment
from datetime import datetime, timedelta
import os

app = Flask(__name__)
DB_FILE = "finance_data.xlsx"

CATEGORIES = ["Housing", "Food", "Transport", "Entertainment", "Health", "Other"]
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
    return {
        "uyu": float(cfg.get("balance_uyu", 0)),
        "usd": float(cfg.get("balance_usd", 0)),
    }


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

    # per-account stats
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

    # trim monthly to last 6
    for acc in stats:
        months = sorted(stats[acc]["monthly"].keys())[-6:]
        stats[acc]["monthly"] = {m: stats[acc]["monthly"][m] for m in months}

    recent = sorted(txns, key=lambda t: str(t["date"]) if t["date"] else "", reverse=True)[:15]

    return jsonify({
        "balances": balances,
        "stats": stats,
        "recent": recent,
        "categories": CATEGORIES,
        "accounts": ACCOUNTS,
        "total_txns": len(txns),
    })


@app.route("/api/transactions", methods=["GET"])
def get_transactions():
    return jsonify(read_sheet("transactions"))


@app.route("/api/fund", methods=["POST"])
def add_funds():
    data = request.json
    acc = data.get("account", "uyu")
    amount = float(data.get("amount", 0))
    balances = get_balances()
    new_bal = balances[acc] + amount
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
    balances = get_balances()
    new_bal = balances[acc] - amount
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
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Finance Dashboard</title>
<script src="https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.js"></script>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f4f5f7;color:#1a1a2e;font-size:14px}
nav{background:#2D5FA6;padding:13px 24px;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px}
nav h1{color:#fff;font-size:17px;font-weight:600}
.nav-accounts{display:flex;gap:10px}
.nav-bal{background:rgba(255,255,255,0.15);border-radius:8px;padding:6px 14px;color:#fff;font-size:13px;font-weight:600;cursor:pointer;display:flex;flex-direction:column;align-items:flex-start;line-height:1.3;transition:background .15s}
.nav-bal:hover{background:rgba(255,255,255,0.25)}
.nav-bal small{font-size:10px;font-weight:400;opacity:.75;text-transform:uppercase;letter-spacing:.04em}
.tabs{display:flex;background:#fff;border-bottom:1px solid #e2e5ea;padding:0 24px;overflow-x:auto}
.tab{padding:12px 16px;cursor:pointer;font-size:13px;font-weight:500;color:#6b7280;border-bottom:2px solid transparent;transition:all .15s;white-space:nowrap}
.tab.active{color:#2D5FA6;border-bottom-color:#2D5FA6}
.page{display:none;padding:24px;max-width:1060px;margin:0 auto}
.page.active{display:block}
.acc-tabs{display:flex;gap:8px;margin-bottom:20px}
.acc-tab{padding:7px 18px;border-radius:99px;font-size:13px;font-weight:500;cursor:pointer;border:1.5px solid #d1d5db;color:#6b7280;background:#fff;transition:all .15s}
.acc-tab.active-uyu{border-color:#2D5FA6;background:#eff6ff;color:#2D5FA6}
.acc-tab.active-usd{border-color:#16a34a;background:#f0fdf4;color:#16a34a}
.metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin-bottom:22px}
.metric{background:#fff;border-radius:10px;padding:16px 18px;border:1px solid #e2e5ea}
.metric-label{font-size:11px;color:#6b7280;text-transform:uppercase;letter-spacing:.05em;margin-bottom:5px}
.metric-value{font-size:24px;font-weight:600}
.metric-sub{font-size:11px;color:#9ca3af;margin-top:3px}
.blue{color:#2D5FA6}.green{color:#16a34a}.red{color:#dc2626}
.charts-row{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-bottom:20px}
.card{background:#fff;border-radius:10px;border:1px solid #e2e5ea;padding:18px}
.card h3{font-size:13px;font-weight:600;color:#374151;margin-bottom:14px}
table{width:100%;border-collapse:collapse}
th{text-align:left;font-size:11px;color:#6b7280;text-transform:uppercase;letter-spacing:.05em;padding:8px 10px;border-bottom:1px solid #e2e5ea;font-weight:500}
td{padding:8px 10px;border-bottom:1px solid #f3f4f6;font-size:13px;vertical-align:middle}
tr:last-child td{border-bottom:none}
tr:hover td{background:#f9fafb}
.badge{display:inline-block;padding:2px 8px;border-radius:99px;font-size:11px;font-weight:500}
.badge-expense{background:#fef2f2;color:#dc2626}
.badge-fund{background:#f0fdf4;color:#16a34a}
.badge-uyu{background:#eff6ff;color:#2D5FA6}
.badge-usd{background:#f0fdf4;color:#16a34a}
.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:16px}
.form-group{display:flex;flex-direction:column;gap:5px}
.form-group label{font-size:12px;font-weight:500;color:#374151}
input,select{border:1px solid #d1d5db;border-radius:6px;padding:8px 10px;font-size:13px;width:100%;outline:none;background:#fff;color:#1a1a2e}
input:focus,select:focus{border-color:#2D5FA6;box-shadow:0 0 0 2px rgba(45,95,166,.12)}
.btn{padding:9px 20px;border-radius:7px;font-size:13px;font-weight:500;cursor:pointer;border:none;transition:all .15s}
.btn-primary{background:#2D5FA6;color:#fff}.btn-primary:hover{background:#1e4a8a}
.btn-green{background:#16a34a;color:#fff}.btn-green:hover{background:#15803d}
.btn-danger{background:#fee2e2;color:#dc2626;border:none;padding:4px 10px;border-radius:5px;cursor:pointer;font-size:12px}
.btn-danger:hover{background:#fca5a5}
.msg{margin-top:10px;font-size:12px;min-height:18px}
.msg.ok{color:#16a34a}.msg.err{color:#dc2626}
.type-toggle{display:flex;border:1px solid #d1d5db;border-radius:7px;overflow:hidden;margin-bottom:18px}
.type-btn{flex:1;padding:10px;text-align:center;cursor:pointer;font-size:13px;font-weight:500;color:#6b7280;background:#fff;border:none;transition:all .15s}
.type-btn.active-expense{background:#fef2f2;color:#dc2626}
.type-btn.active-fund{background:#f0fdf4;color:#16a34a}
.section-title{font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase;letter-spacing:.06em;margin:0 0 12px}
.settings-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px;max-width:600px}
@media(max-width:580px){.charts-row,.form-grid,.settings-grid{grid-template-columns:1fr}.nav-accounts{flex-direction:column;gap:6px}}
</style>
</head>
<body>
<nav>
  <h1>Finance Dashboard</h1>
  <div class="nav-accounts">
    <div class="nav-bal" onclick="showTab('overview');setAccTab('uyu')">
      <small>Pesos (UYU)</small>
      <span id="nav-uyu">—</span>
    </div>
    <div class="nav-bal" onclick="showTab('overview');setAccTab('usd')">
      <small>Dollars (USD)</small>
      <span id="nav-usd">—</span>
    </div>
  </div>
</nav>
<div class="tabs">
  <div class="tab active" onclick="showTab('overview')">Overview</div>
  <div class="tab" onclick="showTab('add')">+ Add</div>
  <div class="tab" onclick="showTab('transactions')">History</div>
  <div class="tab" onclick="showTab('settings')">Settings</div>
</div>

<!-- OVERVIEW -->
<div class="page active" id="page-overview">
  <div class="acc-tabs">
    <button class="acc-tab active-uyu" id="acctab-uyu" onclick="setAccTab('uyu')">$U Pesos</button>
    <button class="acc-tab" id="acctab-usd" onclick="setAccTab('usd')">US$ Dollars</button>
  </div>
  <div class="metrics" id="metrics"></div>
  <div class="charts-row">
    <div class="card">
      <h3 id="bar-title">Money in vs out</h3>
      <div style="position:relative;height:210px"><canvas id="barChart" role="img" aria-label="Monthly funds in vs out"></canvas></div>
    </div>
    <div class="card">
      <h3>Spending by category</h3>
      <div style="position:relative;height:210px"><canvas id="pieChart" role="img" aria-label="Spending by category"></canvas></div>
    </div>
  </div>
  <div class="card">
    <h3>Recent transactions</h3>
    <div style="overflow-x:auto"><table>
      <thead><tr><th>Date</th><th>Description</th><th>Category</th><th>Account</th><th>Type</th><th>Amount</th><th></th></tr></thead>
      <tbody id="recent-table"></tbody>
    </table></div>
  </div>
</div>

<!-- ADD -->
<div class="page" id="page-add">
  <div class="card" style="max-width:480px">
    <div class="type-toggle">
      <button class="type-btn active-expense" id="btn-expense" onclick="setType('expense')">— Expense</button>
      <button class="type-btn" id="btn-fund" onclick="setType('fund')">+ Add funds</button>
    </div>
    <div class="form-grid">
      <div class="form-group">
        <label>Account</label>
        <select id="new-account" onchange="updateAccountHint()">
          <option value="uyu">Pesos (UYU)</option>
          <option value="usd">Dollars (USD)</option>
        </select>
      </div>
      <div class="form-group">
        <label>Amount</label>
        <div style="position:relative">
          <span id="currency-hint" style="position:absolute;left:10px;top:50%;transform:translateY(-50%);font-size:13px;color:#6b7280;pointer-events:none">$U</span>
          <input type="number" id="new-amount" placeholder="0.00" step="0.01" min="0" style="padding-left:32px">
        </div>
      </div>
      <div class="form-group">
        <label>Date</label>
        <input type="date" id="new-date">
      </div>
      <div class="form-group" id="cat-group">
        <label>Category</label>
        <select id="new-cat"></select>
      </div>
      <div class="form-group" style="grid-column:1/-1">
        <label>Description</label>
        <input type="text" id="new-desc" placeholder="e.g. Supermarket">
      </div>
    </div>
    <button class="btn btn-primary" id="submit-btn" onclick="submitEntry()">Save expense</button>
    <p class="msg" id="add-msg"></p>
  </div>
</div>

<!-- HISTORY -->
<div class="page" id="page-transactions">
  <div class="card">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;flex-wrap:wrap;gap:8px">
      <span class="section-title" style="margin:0">Transaction history</span>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <select id="filter-account" onchange="loadTransactions()" style="width:auto">
          <option value="">All accounts</option>
          <option value="uyu">Pesos</option>
          <option value="usd">Dollars</option>
        </select>
        <input type="month" id="filter-month" onchange="loadTransactions()" style="width:auto">
      </div>
    </div>
    <div style="overflow-x:auto"><table>
      <thead><tr><th>Date</th><th>Description</th><th>Category</th><th>Account</th><th>Type</th><th>Amount</th><th></th></tr></thead>
      <tbody id="txn-table"></tbody>
    </table></div>
  </div>
</div>

<!-- SETTINGS -->
<div class="page" id="page-settings">
  <p class="section-title">Correct account balances</p>
  <p style="font-size:13px;color:#6b7280;margin-bottom:18px">Set these to match your actual bank balances. Won't add a transaction — just corrects the numbers.</p>
  <div class="settings-grid">
    <div class="card">
      <h3>$U Pesos (UYU)</h3>
      <div style="display:flex;gap:8px;margin-top:12px">
        <input type="number" id="bal-uyu" placeholder="0.00" step="0.01">
        <button class="btn btn-primary" onclick="saveBalance('uyu')">Save</button>
      </div>
      <p class="msg" id="msg-uyu"></p>
    </div>
    <div class="card">
      <h3>US$ Dollars (USD)</h3>
      <div style="display:flex;gap:8px;margin-top:12px">
        <input type="number" id="bal-usd" placeholder="0.00" step="0.01">
        <button class="btn btn-green" onclick="saveBalance('usd')">Save</button>
      </div>
      <p class="msg" id="msg-usd"></p>
    </div>
  </div>
</div>

<script>
let summary = {};
let barChart, pieChart;
let currentType = 'expense';
let currentAcc = 'uyu';
const COLORS = ['#2D5FA6','#f59e0b','#16a34a','#ec4899','#8b5cf6','#6b7280','#0ea5e9'];
const SYMBOLS = {uyu: '$U', usd: 'US$'};

function fmt(n, acc) {
  const sym = acc ? SYMBOLS[acc] : '$';
  const abs = Math.abs(Number(n));
  const s = sym + ' ' + abs.toLocaleString('en-US', {minimumFractionDigits:2, maximumFractionDigits:2});
  return Number(n) < 0 ? '-' + s : s;
}

function showTab(name) {
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  const idx = ['overview','add','transactions','settings'].indexOf(name);
  document.querySelectorAll('.tab')[idx].classList.add('active');
  document.getElementById('page-'+name).classList.add('active');
  if (name === 'transactions') loadTransactions();
  if (name === 'settings') {
    const b = summary.balances || {};
    document.getElementById('bal-uyu').value = (b.uyu||0).toFixed(2);
    document.getElementById('bal-usd').value = (b.usd||0).toFixed(2);
  }
}

function setAccTab(acc) {
  currentAcc = acc;
  ['uyu','usd'].forEach(a => {
    const el = document.getElementById('acctab-'+a);
    el.className = 'acc-tab' + (a === acc ? ' active-'+a : '');
  });
  renderMetrics();
  renderCharts();
}

function setType(type) {
  currentType = type;
  document.getElementById('btn-expense').className = 'type-btn' + (type==='expense'?' active-expense':'');
  document.getElementById('btn-fund').className = 'type-btn' + (type==='fund'?' active-fund':'');
  document.getElementById('cat-group').style.display = type==='expense' ? '' : 'none';
  document.getElementById('submit-btn').textContent = type==='expense' ? 'Save expense' : 'Add funds';
  document.getElementById('submit-btn').className = 'btn ' + (type==='expense' ? 'btn-primary' : 'btn-green');
}

function updateAccountHint() {
  const acc = document.getElementById('new-account').value;
  document.getElementById('currency-hint').textContent = SYMBOLS[acc];
}

async function loadSummary() {
  const r = await fetch('/api/summary');
  summary = await r.json();
  const b = summary.balances || {};
  document.getElementById('nav-uyu').textContent = fmt(b.uyu||0, 'uyu');
  document.getElementById('nav-usd').textContent = fmt(b.usd||0, 'usd');
  document.getElementById('new-cat').innerHTML = (summary.categories||[]).map(c=>`<option>${c}</option>`).join('');
  renderMetrics();
  renderCharts();
  renderRecent();
}

function renderMetrics() {
  const b = (summary.balances||{})[currentAcc] || 0;
  const st = (summary.stats||{})[currentAcc] || {};
  const l30 = st.last30 || 0;
  document.getElementById('metrics').innerHTML = `
    <div class="metric">
      <div class="metric-label">${currentAcc==='uyu'?'Pesos balance':'Dollar balance'}</div>
      <div class="metric-value ${b>=0?(currentAcc==='uyu'?'blue':'green'):'red'}">${fmt(b, currentAcc)}</div>
      <div class="metric-sub">current balance</div>
    </div>
    <div class="metric">
      <div class="metric-label">Spent (last 30 days)</div>
      <div class="metric-value">${fmt(l30, currentAcc)}</div>
    </div>
    <div class="metric">
      <div class="metric-label">Total transactions</div>
      <div class="metric-value">${summary.total_txns||0}</div>
      <div class="metric-sub">all accounts</div>
    </div>
  `;
}

function renderCharts() {
  const st = (summary.stats||{})[currentAcc] || {};
  const monthly = st.monthly || {};
  const months = Object.keys(monthly);

  if (barChart) barChart.destroy();
  document.getElementById('bar-title').textContent = `Money in vs out — ${currentAcc==='uyu'?'Pesos':'Dollars'}`;
  if (months.length) {
    const labels = months.map(m => { const [y,mo]=m.split('-'); return new Date(y,mo-1).toLocaleString('default',{month:'short',year:'2-digit'}); });
    const accentIn = currentAcc==='uyu' ? '#2D5FA6' : '#16a34a';
    barChart = new Chart(document.getElementById('barChart'), {
      type:'bar',
      data:{labels, datasets:[
        {label:'Funds in', data:months.map(m=>monthly[m].in), backgroundColor:accentIn, borderRadius:4},
        {label:'Expenses', data:months.map(m=>monthly[m].out), backgroundColor:'#f87171', borderRadius:4}
      ]},
      options:{responsive:true,maintainAspectRatio:false,
        plugins:{legend:{labels:{font:{size:11},boxWidth:10}}},
        scales:{x:{grid:{display:false},ticks:{font:{size:11}}},
                y:{grid:{color:'#f0f0f0'},ticks:{font:{size:11},callback:v=>SYMBOLS[currentAcc]+' '+v.toLocaleString()}}}
      }
    });
  }

  const expCat = st.expenses_by_cat || {};
  const cats = Object.keys(expCat);
  if (pieChart) pieChart.destroy();
  if (cats.length) {
    pieChart = new Chart(document.getElementById('pieChart'), {
      type:'doughnut',
      data:{labels:cats, datasets:[{data:cats.map(c=>expCat[c]), backgroundColor:COLORS, borderWidth:2, borderColor:'#fff'}]},
      options:{responsive:true,maintainAspectRatio:false,cutout:'60%',
        plugins:{legend:{position:'right',labels:{font:{size:11},boxWidth:10,padding:8}}}
      }
    });
  }
}

function txnRow(t) {
  const isFund = t.type==='fund';
  const acc = t.account || 'uyu';
  return `<tr>
    <td>${t.date||''}</td>
    <td>${t.description||''}</td>
    <td>${t.category||''}</td>
    <td><span class="badge badge-${acc}">${acc==='uyu'?'$U Pesos':'US$ Dollars'}</span></td>
    <td><span class="badge badge-${t.type}">${isFund?'+ funds':'expense'}</span></td>
    <td style="font-weight:600;color:${isFund?'#16a34a':'#dc2626'}">${isFund?'+':'-'} ${fmt(t.amount, acc)}</td>
    <td><button class="btn-danger" onclick="deleteTxn(${t.id})">✕</button></td>
  </tr>`;
}

function renderRecent() {
  const rows = (summary.recent||[]).map(txnRow).join('');
  document.getElementById('recent-table').innerHTML = rows||'<tr><td colspan="7" style="color:#9ca3af;text-align:center;padding:20px">No transactions yet — add one!</td></tr>';
}

async function loadTransactions() {
  const fm = document.getElementById('filter-month').value;
  const fa = document.getElementById('filter-account').value;
  const r = await fetch('/api/transactions');
  let txns = await r.json();
  if (fm) txns = txns.filter(t => t.date && String(t.date).startsWith(fm));
  if (fa) txns = txns.filter(t => (t.account||'uyu') === fa);
  txns.sort((a,b)=>String(b.date).localeCompare(String(a.date)));
  document.getElementById('txn-table').innerHTML = txns.map(txnRow).join('')
    ||'<tr><td colspan="7" style="color:#9ca3af;text-align:center;padding:20px">No transactions for this period</td></tr>';
}

async function submitEntry() {
  const amount = document.getElementById('new-amount').value;
  const description = document.getElementById('new-desc').value;
  const account = document.getElementById('new-account').value;
  const msg = document.getElementById('add-msg');
  if (!amount || !description) { msg.textContent='Please fill in amount and description.'; msg.className='msg err'; return; }
  const payload = {amount, description, account, date:document.getElementById('new-date').value, category:document.getElementById('new-cat').value};
  const r = await fetch(currentType==='fund'?'/api/fund':'/api/expense', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
  const data = await r.json();
  msg.textContent = '✓ Saved! New '+SYMBOLS[account]+' balance: '+fmt(data.balance, account);
  msg.className='msg ok';
  document.getElementById('new-amount').value='';
  document.getElementById('new-desc').value='';
  loadSummary();
  setTimeout(()=>{msg.textContent='';},4000);
}

async function deleteTxn(id) {
  if (!confirm('Delete this transaction? The balance will be reversed.')) return;
  await fetch('/api/transactions/'+id,{method:'DELETE'});
  loadSummary();
  if (document.getElementById('page-transactions').classList.contains('active')) loadTransactions();
}

async function saveBalance(acc) {
  const val = document.getElementById('bal-'+acc).value;
  const msg = document.getElementById('msg-'+acc);
  if (!val) return;
  await fetch('/api/balance',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({account:acc, balance:val})});
  msg.textContent='✓ Updated to '+fmt(val, acc);
  msg.className='msg ok';
  loadSummary();
  setTimeout(()=>{msg.textContent='';},4000);
}

const today = new Date().toISOString().split('T')[0];
document.getElementById('new-date').value = today;
document.getElementById('filter-month').value = today.slice(0,7);
setType('expense');
loadSummary();
</script>
</body>
</html>"""

if __name__ == "__main__":
    init_db()
    print("\n  Finance Dashboard running at http://localhost:5000\n")
    app.run(debug=False, port=5000)