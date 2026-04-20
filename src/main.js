import "./styles.css";
import { Preferences } from "@capacitor/preferences";
import Chart from "chart.js/auto";

const STORAGE_KEY = "clarifi.mobile.state";
const DEFAULT_CATEGORIES = [
  "Supermercado",
  "Comida",
  "Transporte",
  "Juegos",
  "Estudio",
  "Hogar",
  "Salud",
  "Otros",
];

const ACCOUNTS = {
  uyu: {
    id: "uyu",
    name: "Pesos",
    currency: "UYU",
    symbol: "$U",
    accentClass: "is-uyu",
  },
  usd: {
    id: "usd",
    name: "Dólares",
    currency: "USD",
    symbol: "US$",
    accentClass: "is-usd",
  },
};

const DEFAULT_STATE = {
  schemaVersion: 1,
  profileName: "",
  balances: { uyu: 0, usd: 0 },
  categories: [...DEFAULT_CATEGORIES],
  transactions: [],
};

let state = structuredClone(DEFAULT_STATE);
let currentAccount = "uyu";
let currentType = "expense";
let flowChart = null;
let categoryChart = null;

function uid() {
  return Math.floor(Date.now() + Math.random() * 1000);
}

function parseAmount(value) {
  const amount = Number.parseFloat(value);
  if (!Number.isFinite(amount)) {
    return 0;
  }
  return Math.round(amount * 100) / 100;
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

function formatAmount(amount, account) {
  const conf = ACCOUNTS[account] ?? ACCOUNTS.uyu;
  return new Intl.NumberFormat("es-UY", {
    style: "currency",
    currency: conf.currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(Number(amount) || 0);
}

function normalizeTransaction(txn) {
  const account = txn?.account === "usd" ? "usd" : "uyu";
  const type = txn?.type === "fund" ? "fund" : "expense";
  return {
    id: Number(txn?.id) || uid(),
    date: typeof txn?.date === "string" && txn.date ? txn.date : today(),
    description: String(txn?.description ?? "").trim(),
    amount: parseAmount(txn?.amount),
    category: String(txn?.category ?? "Otros").trim() || "Otros",
    type,
    account,
  };
}

function normalizeState(raw) {
  const balances = raw?.balances ?? {};
  const categories = Array.isArray(raw?.categories)
    ? [...new Set(raw.categories.map((item) => String(item).trim()).filter(Boolean))]
    : [...DEFAULT_CATEGORIES];
  const transactions = Array.isArray(raw?.transactions)
    ? raw.transactions.map(normalizeTransaction)
    : [];

  return {
    schemaVersion: 1,
    profileName: String(raw?.profileName ?? "").trim(),
    balances: {
      uyu: parseAmount(balances.uyu),
      usd: parseAmount(balances.usd),
    },
    categories: categories.length ? categories : [...DEFAULT_CATEGORIES],
    transactions: transactions
      .filter((txn) => txn.amount > 0)
      .sort((a, b) => String(b.date).localeCompare(String(a.date))),
  };
}

async function loadState() {
  const saved = await Preferences.get({ key: STORAGE_KEY });
  if (!saved.value) {
    state = structuredClone(DEFAULT_STATE);
    return;
  }

  try {
    state = normalizeState(JSON.parse(saved.value));
  } catch (error) {
    console.error("No se pudo leer el estado guardado", error);
    state = structuredClone(DEFAULT_STATE);
  }
}

async function persistState() {
  await Preferences.set({ key: STORAGE_KEY, value: JSON.stringify(state) });
}

function computeSummary() {
  const stats = {
    uyu: { expensesByCat: {}, monthly: {}, last30: 0 },
    usd: { expensesByCat: {}, monthly: {}, last30: 0 },
  };
  const cutoff = new Date();
  cutoff.setDate(cutoff.getDate() - 30);

  state.transactions.forEach((txn) => {
    const bucket = stats[txn.account];
    const month = txn.date.slice(0, 7);
    const txnDate = new Date(`${txn.date}T00:00:00`);

    if (!bucket.monthly[month]) {
      bucket.monthly[month] = { in: 0, out: 0 };
    }

    if (txn.type === "fund") {
      bucket.monthly[month].in += txn.amount;
    } else {
      bucket.monthly[month].out += txn.amount;
      bucket.expensesByCat[txn.category] =
        (bucket.expensesByCat[txn.category] ?? 0) + txn.amount;
      if (txnDate >= cutoff) {
        bucket.last30 += txn.amount;
      }
    }
  });

  Object.values(stats).forEach((bucket) => {
    const lastSixMonths = Object.keys(bucket.monthly).sort().slice(-6);
    bucket.monthly = Object.fromEntries(
      lastSixMonths.map((month) => [month, bucket.monthly[month]]),
    );
  });

  return {
    balances: state.balances,
    stats,
    totalTxns: state.transactions.length,
    recent: state.transactions.slice(0, 12),
  };
}

function appShell() {
  return `
    <div class="app-shell">
      <div class="bg-glow bg-glow-one"></div>
      <div class="bg-glow bg-glow-two"></div>
      <header class="topbar">
        <div>
          <p class="eyebrow">Control local</p>
          <h1>ClariFi Mobile</h1>
        </div>
        <div class="phone-badge">1 celular = 1 perfil</div>
      </header>

      <section class="hero-card">
        <div>
          <p class="eyebrow">Perfil actual</p>
          <h2 id="profile-label">Este celular</h2>
          <p class="hero-copy">
            Tus datos quedan guardados en este dispositivo. Si tu novia instala la app en su celular,
            tendrá su propio historial por separado.
          </p>
        </div>
        <div class="hero-actions">
          <button class="ghost-btn" data-action="export">Exportar respaldo</button>
          <label class="ghost-btn import-btn" for="import-file">Importar JSON</label>
          <input id="import-file" type="file" accept="application/json" hidden />
        </div>
      </section>

      <nav class="tabs">
        <button class="tab-btn is-active" data-tab="overview">Resumen</button>
        <button class="tab-btn" data-tab="add">Agregar</button>
        <button class="tab-btn" data-tab="history">Historial</button>
        <button class="tab-btn" data-tab="settings">Ajustes</button>
      </nav>

      <main class="page-stack">
        <section class="page is-active" data-page="overview">
          <div class="account-switch" id="account-switch"></div>
          <div class="metrics" id="metrics"></div>
          <div class="chart-grid">
            <article class="panel">
              <div class="panel-head">
                <span>Flujo mensual</span>
              </div>
              <div class="chart-wrap">
                <canvas id="flow-chart"></canvas>
              </div>
            </article>
            <article class="panel">
              <div class="panel-head">
                <span>Gastos por categoría</span>
              </div>
              <div class="chart-wrap">
                <canvas id="category-chart"></canvas>
              </div>
            </article>
          </div>
          <article class="panel">
            <div class="panel-head">
              <span>Movimientos recientes</span>
            </div>
            <div class="table-shell">
              <table>
                <thead>
                  <tr>
                    <th>Fecha</th>
                    <th>Detalle</th>
                    <th>Cuenta</th>
                    <th>Tipo</th>
                    <th>Monto</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody id="recent-table"></tbody>
              </table>
            </div>
          </article>
        </section>

        <section class="page" data-page="add">
          <article class="panel form-panel">
            <div class="type-toggle">
              <button class="type-btn is-expense is-active" data-type="expense">Gasto</button>
              <button class="type-btn is-fund" data-type="fund">Ingreso</button>
            </div>

            <form id="entry-form" class="form-grid">
              <label class="field">
                <span>Cuenta</span>
                <select id="entry-account">
                  <option value="uyu">Pesos (UYU)</option>
                  <option value="usd">Dólares (USD)</option>
                </select>
              </label>
              <label class="field">
                <span>Monto</span>
                <input id="entry-amount" type="number" min="0" step="0.01" placeholder="0.00" required />
              </label>
              <label class="field">
                <span>Fecha</span>
                <input id="entry-date" type="date" required />
              </label>
              <label class="field" id="category-field">
                <span>Categoría</span>
                <select id="entry-category"></select>
              </label>
              <label class="field field-full">
                <span>Descripción</span>
                <input id="entry-description" type="text" maxlength="80" placeholder="Ej. supermercado o sueldo" required />
              </label>
              <button class="primary-btn field-full" type="submit" id="submit-entry">Guardar movimiento</button>
            </form>

            <p class="inline-note" id="entry-message"></p>
          </article>
        </section>

        <section class="page" data-page="history">
          <article class="panel">
            <div class="history-toolbar">
              <label class="field">
                <span>Cuenta</span>
                <select id="history-account">
                  <option value="all">Todas</option>
                  <option value="uyu">Sólo pesos</option>
                  <option value="usd">Sólo dólares</option>
                </select>
              </label>
              <label class="field">
                <span>Mes</span>
                <input id="history-month" type="month" />
              </label>
            </div>
            <div class="table-shell">
              <table>
                <thead>
                  <tr>
                    <th>Fecha</th>
                    <th>Detalle</th>
                    <th>Categoría</th>
                    <th>Cuenta</th>
                    <th>Tipo</th>
                    <th>Monto</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody id="history-table"></tbody>
              </table>
            </div>
          </article>
        </section>

        <section class="page" data-page="settings">
          <article class="panel settings-panel">
            <div class="settings-grid">
              <label class="field field-full">
                <span>Nombre del perfil</span>
                <input id="profile-name" type="text" maxlength="40" placeholder="Ej. Fede" />
              </label>
              <button class="secondary-btn field-full" data-action="save-profile" type="button">Guardar nombre</button>

              <label class="field">
                <span>Saldo pesos</span>
                <input id="balance-uyu" type="number" min="-99999999" step="0.01" />
              </label>
              <button class="secondary-btn" data-action="save-balance" data-account="uyu" type="button">Actualizar saldo UYU</button>

              <label class="field">
                <span>Saldo dólares</span>
                <input id="balance-usd" type="number" min="-99999999" step="0.01" />
              </label>
              <button class="secondary-btn" data-action="save-balance" data-account="usd" type="button">Actualizar saldo USD</button>

              <label class="field field-full">
                <span>Nueva categoría</span>
                <input id="new-category" type="text" maxlength="30" placeholder="Ej. Regalos" />
              </label>
              <button class="secondary-btn field-full" data-action="add-category" type="button">Agregar categoría</button>

              <button class="danger-btn field-full" data-action="reset" type="button">Borrar todos mis datos</button>
            </div>
            <p class="inline-note" id="settings-message"></p>
          </article>
        </section>
      </main>
    </div>
  `;
}

function renderLayout() {
  const root = document.querySelector("#app");
  root.innerHTML = appShell();
  document.querySelector("#entry-date").value = today();
  document.querySelector("#history-month").value = today().slice(0, 7);
  bindEvents();
}

function bindEvents() {
  document.querySelectorAll("[data-tab]").forEach((button) => {
    button.addEventListener("click", () => setTab(button.dataset.tab));
  });

  document.querySelectorAll("[data-type]").forEach((button) => {
    button.addEventListener("click", () => setType(button.dataset.type));
  });

  document.querySelector("#entry-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    await submitEntry();
  });

  document.querySelector("#history-account").addEventListener("change", renderHistory);
  document.querySelector("#history-month").addEventListener("change", renderHistory);

  document.querySelector("#import-file").addEventListener("change", importBackup);

  document.querySelectorAll("[data-action='save-balance']").forEach((button) => {
    button.addEventListener("click", async () => saveBalance(button.dataset.account));
  });

  document
    .querySelector("[data-action='save-profile']")
    .addEventListener("click", saveProfileName);
  document
    .querySelector("[data-action='add-category']")
    .addEventListener("click", addCategory);
  document
    .querySelector("[data-action='reset']")
    .addEventListener("click", resetAllData);
  document
    .querySelector("[data-action='export']")
    .addEventListener("click", exportBackup);
}

function setTab(tab) {
  currentTab = tab;
  document.querySelectorAll("[data-tab]").forEach((button) => {
    button.classList.toggle("is-active", button.dataset.tab === tab);
  });
  document.querySelectorAll("[data-page]").forEach((page) => {
    page.classList.toggle("is-active", page.dataset.page === tab);
  });
  if (tab === "history") {
    renderHistory();
  }
  if (tab === "settings") {
    fillSettings();
  }
}

function setType(type) {
  currentType = type === "fund" ? "fund" : "expense";
  document.querySelectorAll("[data-type]").forEach((button) => {
    button.classList.toggle("is-active", button.dataset.type === currentType);
  });
  document.querySelector("#category-field").hidden = currentType === "fund";
  document.querySelector("#submit-entry").textContent =
    currentType === "fund" ? "Guardar ingreso" : "Guardar gasto";
}

function renderAccountSwitch() {
  const wrap = document.querySelector("#account-switch");
  wrap.innerHTML = Object.values(ACCOUNTS)
    .map(
      (account) => `
        <button
          class="account-pill ${account.accentClass} ${currentAccount === account.id ? "is-active" : ""}"
          data-account="${account.id}"
        >
          ${account.symbol} ${account.name}
        </button>
      `,
    )
    .join("");

  wrap.querySelectorAll("[data-account]").forEach((button) => {
    button.addEventListener("click", () => {
      currentAccount = button.dataset.account;
      renderDashboard();
    });
  });
}

function metricCard(label, value, note, accentClass = "") {
  return `
    <article class="metric-card ${accentClass}">
      <span>${label}</span>
      <strong>${value}</strong>
      <small>${note}</small>
    </article>
  `;
}

function renderMetrics(summary) {
  const balance = summary.balances[currentAccount] ?? 0;
  const bucket = summary.stats[currentAccount];
  document.querySelector("#metrics").innerHTML = [
    metricCard("Saldo actual", formatAmount(balance, currentAccount), "guardado localmente", ACCOUNTS[currentAccount].accentClass),
    metricCard("Gastado en 30 días", formatAmount(bucket.last30, currentAccount), "último mes"),
    metricCard("Movimientos", String(summary.totalTxns), "sumando ambas cuentas"),
  ].join("");
}

function renderTableRows(target, rows, emptyMessage, includeCategory) {
  const table = document.querySelector(target);
  if (!rows.length) {
    table.innerHTML = `<tr><td colspan="${includeCategory ? 7 : 6}" class="empty-row">${emptyMessage}</td></tr>`;
    return;
  }

  table.innerHTML = rows
    .map((txn) => {
      const account = ACCOUNTS[txn.account];
      const amountPrefix = txn.type === "fund" ? "+" : "−";
      return `
        <tr>
          <td>${txn.date}</td>
          <td>${txn.description}</td>
          ${includeCategory ? `<td>${txn.category}</td>` : ""}
          <td><span class="mini-pill ${account.accentClass}">${account.symbol} ${account.name}</span></td>
          <td><span class="mini-pill ${txn.type === "fund" ? "is-positive" : "is-danger"}">${txn.type === "fund" ? "Ingreso" : "Gasto"}</span></td>
          <td class="amount-cell ${txn.type === "fund" ? "is-positive" : "is-danger"}">${amountPrefix} ${formatAmount(txn.amount, txn.account)}</td>
          <td><button class="icon-delete" data-delete="${txn.id}" aria-label="Eliminar">×</button></td>
        </tr>
      `;
    })
    .join("");

  table.querySelectorAll("[data-delete]").forEach((button) => {
    button.addEventListener("click", async () => deleteTransaction(Number(button.dataset.delete)));
  });
}

function destroyCharts() {
  flowChart?.destroy();
  categoryChart?.destroy();
  flowChart = null;
  categoryChart = null;
}

function renderCharts(summary) {
  destroyCharts();
  const bucket = summary.stats[currentAccount];
  const months = Object.keys(bucket.monthly);

  if (months.length) {
    flowChart = new Chart(document.querySelector("#flow-chart"), {
      type: "bar",
      data: {
        labels: months.map((month) => {
          const [year, number] = month.split("-");
          return new Date(Number(year), Number(number) - 1, 1).toLocaleDateString("es-UY", {
            month: "short",
          });
        }),
        datasets: [
          {
            label: "Ingresos",
            data: months.map((month) => bucket.monthly[month].in),
            backgroundColor: "rgba(61, 224, 176, 0.70)",
            borderRadius: 14,
          },
          {
            label: "Gastos",
            data: months.map((month) => bucket.monthly[month].out),
            backgroundColor: "rgba(255, 111, 97, 0.75)",
            borderRadius: 14,
          },
        ],
      },
      options: {
        maintainAspectRatio: false,
        plugins: {
          legend: {
            labels: { color: "#d0def0" },
          },
        },
        scales: {
          x: {
            ticks: { color: "#8ea7c2" },
            grid: { display: false },
          },
          y: {
            ticks: { color: "#8ea7c2" },
            grid: { color: "rgba(208, 222, 240, 0.10)" },
          },
        },
      },
    });
  }

  const categories = Object.keys(bucket.expensesByCat);
  if (categories.length) {
    categoryChart = new Chart(document.querySelector("#category-chart"), {
      type: "doughnut",
      data: {
        labels: categories,
        datasets: [
          {
            data: categories.map((category) => bucket.expensesByCat[category]),
            backgroundColor: [
              "#78e9ff",
              "#3de0b0",
              "#ffb703",
              "#ff7b72",
              "#5eead4",
              "#a3e635",
              "#f97316",
              "#60a5fa",
            ],
            borderWidth: 0,
          },
        ],
      },
      options: {
        maintainAspectRatio: false,
        cutout: "68%",
        plugins: {
          legend: {
            position: "bottom",
            labels: { color: "#d0def0", boxWidth: 10 },
          },
        },
      },
    });
  }
}

function renderDashboard() {
  const summary = computeSummary();
  renderAccountSwitch();
  renderMetrics(summary);
  renderTableRows("#recent-table", summary.recent, "Todavía no hay movimientos guardados.", false);
  renderCharts(summary);
  renderProfileLabel();
}

function filteredHistory() {
  const selectedAccount = document.querySelector("#history-account").value;
  const selectedMonth = document.querySelector("#history-month").value;
  return state.transactions.filter((txn) => {
    const accountMatch = selectedAccount === "all" || txn.account === selectedAccount;
    const monthMatch = !selectedMonth || txn.date.startsWith(selectedMonth);
    return accountMatch && monthMatch;
  });
}

function renderHistory() {
  renderTableRows("#history-table", filteredHistory(), "No hay movimientos para ese filtro.", true);
}

function renderCategoryOptions() {
  document.querySelector("#entry-category").innerHTML = state.categories
    .map((category) => `<option value="${category}">${category}</option>`)
    .join("");
}

function fillSettings() {
  document.querySelector("#profile-name").value = state.profileName;
  document.querySelector("#balance-uyu").value = String(state.balances.uyu ?? 0);
  document.querySelector("#balance-usd").value = String(state.balances.usd ?? 0);
}

function renderProfileLabel() {
  document.querySelector("#profile-label").textContent = state.profileName || "Este celular";
}

function setInlineMessage(target, message, tone = "normal") {
  const node = document.querySelector(target);
  node.textContent = message;
  node.dataset.tone = tone;
  window.clearTimeout(node._messageTimer);
  node._messageTimer = window.setTimeout(() => {
    node.textContent = "";
    node.dataset.tone = "normal";
  }, 3500);
}

async function submitEntry() {
  const description = document.querySelector("#entry-description").value.trim();
  const amount = parseAmount(document.querySelector("#entry-amount").value);
  const account = document.querySelector("#entry-account").value;
  const date = document.querySelector("#entry-date").value || today();
  const category = document.querySelector("#entry-category").value || "Otros";

  if (!description || amount <= 0) {
    setInlineMessage("#entry-message", "Completá descripción y monto para guardar.", "error");
    return;
  }

  const transaction = normalizeTransaction({
    id: uid(),
    account,
    amount,
    date,
    description,
    category: currentType === "fund" ? "Ingreso" : category,
    type: currentType,
  });

  state.transactions.unshift(transaction);
  state.transactions.sort((a, b) => String(b.date).localeCompare(String(a.date)));
  state.balances[account] = parseAmount(
    state.balances[account] + (currentType === "fund" ? amount : -amount),
  );

  await persistState();
  document.querySelector("#entry-form").reset();
  document.querySelector("#entry-date").value = today();
  renderCategoryOptions();
  setType(currentType);
  renderDashboard();
  renderHistory();
  fillSettings();
  setInlineMessage(
    "#entry-message",
    `${currentType === "fund" ? "Ingreso" : "Gasto"} guardado en ${safeAccount(account).name}.`,
    "success",
  );
}

function safeAccount(account) {
  return ACCOUNTS[account] ?? ACCOUNTS.uyu;
}

async function deleteTransaction(id) {
  const transaction = state.transactions.find((txn) => txn.id === id);
  if (!transaction) {
    return;
  }

  const ok = window.confirm("¿Querés eliminar este movimiento?");
  if (!ok) {
    return;
  }

  state.transactions = state.transactions.filter((txn) => txn.id !== id);
  state.balances[transaction.account] = parseAmount(
    state.balances[transaction.account] +
      (transaction.type === "fund" ? -transaction.amount : transaction.amount),
  );
  await persistState();
  renderDashboard();
  renderHistory();
  fillSettings();
}

async function saveBalance(account) {
  const value = parseAmount(document.querySelector(`#balance-${account}`).value);
  state.balances[account] = value;
  await persistState();
  renderDashboard();
  fillSettings();
  setInlineMessage(
    "#settings-message",
    `Saldo ${safeAccount(account).currency} actualizado a ${formatAmount(value, account)}.`,
    "success",
  );
}

async function saveProfileName() {
  state.profileName = document.querySelector("#profile-name").value.trim();
  await persistState();
  renderProfileLabel();
  setInlineMessage("#settings-message", "Nombre del perfil actualizado.", "success");
}

async function addCategory() {
  const input = document.querySelector("#new-category");
  const value = input.value.trim();
  if (!value) {
    setInlineMessage("#settings-message", "Escribí una categoría antes de agregarla.", "error");
    return;
  }
  if (state.categories.includes(value)) {
    setInlineMessage("#settings-message", "Esa categoría ya existe.", "error");
    return;
  }

  state.categories.push(value);
  state.categories.sort((a, b) => a.localeCompare(b, "es"));
  await persistState();
  renderCategoryOptions();
  input.value = "";
  setInlineMessage("#settings-message", "Categoría agregada.", "success");
}

async function resetAllData() {
  const ok = window.confirm("Esto borra todos los movimientos y saldos de este celular. ¿Seguimos?");
  if (!ok) {
    return;
  }
  state = structuredClone(DEFAULT_STATE);
  await persistState();
  document.querySelector("#entry-form").reset();
  document.querySelector("#entry-date").value = today();
  renderCategoryOptions();
  setType("expense");
  renderDashboard();
  renderHistory();
  fillSettings();
  setInlineMessage("#settings-message", "Se borraron los datos locales de este dispositivo.", "success");
}

function downloadText(filename, content) {
  const blob = new Blob([content], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

function exportBackup() {
  const payload = {
    exportedAt: new Date().toISOString(),
    app: "ClariFi Mobile",
    ...state,
  };
  const filename = `clarifi-backup-${today()}.json`;
  downloadText(filename, JSON.stringify(payload, null, 2));
}

async function importBackup(event) {
  const file = event.target.files?.[0];
  if (!file) {
    return;
  }

  try {
    const text = await file.text();
    const imported = normalizeState(JSON.parse(text));
    state = imported;
    await persistState();
    document.querySelector("#entry-form").reset();
    document.querySelector("#entry-date").value = today();
    renderCategoryOptions();
    setType("expense");
    renderDashboard();
    renderHistory();
    fillSettings();
    setInlineMessage("#settings-message", "Datos importados correctamente.", "success");
    setTab("settings");
  } catch (error) {
    console.error(error);
    setInlineMessage("#settings-message", "No pude importar ese archivo JSON.", "error");
    setTab("settings");
  } finally {
    event.target.value = "";
  }
}

async function init() {
  await loadState();
  renderLayout();
  renderCategoryOptions();
  renderDashboard();
  renderHistory();
  fillSettings();
  setType("expense");
}

init();
