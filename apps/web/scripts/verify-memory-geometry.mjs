import {
  existsSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath, pathToFileURL } from "node:url";

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const WEB_ROOT = path.resolve(path.dirname(SCRIPT_PATH), "..");
const SHELL_CSS = readFileSync(path.join(WEB_ROOT, "src/index.css"), "utf8");
const COMPONENT_SHELL_CSS_PATH = path.join(
  WEB_ROOT,
  "src/components/shell/CortexShell.css",
);
const COMPONENT_SHELL_CSS = existsSync(COMPONENT_SHELL_CSS_PATH)
  ? readFileSync(COMPONENT_SHELL_CSS_PATH, "utf8")
  : "";
const HEADER_CSS = readFileSync(
  path.join(WEB_ROOT, "src/components/header/CortexPageHeader.css"),
  "utf8",
);
const WORKSPACE_CSS = readFileSync(
  path.join(WEB_ROOT, "src/components/workspace/OperationalWorkspace.css"),
  "utf8",
);
const MEMORY_CSS = readFileSync(
  path.join(WEB_ROOT, "src/features/home/memory/MemoryLedger.css"),
  "utf8",
);
const BROWSER = process.env.CORTEX_BROWSER_BIN ?? [
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/Applications/Chromium.app/Contents/MacOS/Chromium",
].find(existsSync);
const SCENARIOS = [
  { viewport: 1440, sidebar: 248 },
  { viewport: 1440, sidebar: 360 },
  { viewport: 901, sidebar: 248 },
  { viewport: 901, sidebar: 360 },
  { viewport: 375, sidebar: 0 },
];
const USE_PROCESS_GROUP = process.platform !== "win32";
const CDP_REQUEST_TIMEOUT_MS = 10_000;
const TARGET_DISCOVERY_TIMEOUT_MS = 10_000;
const WINDOWS_TASKKILL_TIMEOUT_MS = 5_000;
const RETRYABLE_CLEANUP_CODES = new Set([
  "EACCES",
  "EBUSY",
  "ENOTEMPTY",
  "EPERM",
]);

if (!BROWSER) {
  throw new Error("CORTEX_BROWSER_BIN é obrigatório para verificar a geometria real da Memória.");
}

const temporaryDirectory = mkdtempSync(
  path.join(os.tmpdir(), "cortex-memory-geometry-"),
);
let browserProcess;
let protocol;
let executionError;

try {
  const profile = path.join(temporaryDirectory, "browser-profile");
  const browser = spawn(BROWSER, [
    "--headless=new",
    "--disable-gpu",
    "--disable-extensions",
    "--no-first-run",
    "--allow-file-access-from-files",
    "--remote-debugging-port=0",
    `--user-data-dir=${profile}`,
    "about:blank",
  ], {
    detached: USE_PROCESS_GROUP,
    stdio: "ignore",
  });
  browserProcess = browser;
  const port = await readDevToolsPort(profile, browser);
  const targets = await fetch(`http://127.0.0.1:${port}/json/list`, {
    signal: AbortSignal.timeout(TARGET_DISCOVERY_TIMEOUT_MS),
  }).then((response) => response.json());
  const page = targets.find((target) => target.type === "page");
  if (!page?.webSocketDebuggerUrl) {
    throw new Error("O browser não expôs uma página para a verificação geométrica.");
  }
  protocol = await connectDevTools(page.webSocketDebuggerUrl);
  for (const scenario of SCENARIOS) {
    await verifyScenario(scenario, protocol);
  }
} catch (error) {
  executionError = error;
}

const cleanupErrors = [];
try {
  await stopBrowser(browserProcess, protocol);
} catch (error) {
  cleanupErrors.push(error);
}
try {
  await removeTemporaryDirectory(temporaryDirectory);
} catch (error) {
  cleanupErrors.push(error);
}

if (executionError) {
  if (cleanupErrors.length > 0) {
    process.stderr.write(
      `A limpeza também falhou: ${cleanupErrors.map(errorMessage).join("; ")}\n`,
    );
  }
  throw executionError;
}
if (cleanupErrors.length > 0) {
  throw new AggregateError(
    cleanupErrors,
    "Não foi possível encerrar e limpar o browser de geometria da Memória.",
  );
}

const scenarioSummary = SCENARIOS
  .map(({ viewport, sidebar }) => `${viewport}/${sidebar}`)
  .join(", ");
process.stdout.write(
  `Memory geometry verified: ${SCENARIOS.length} scenarios [${scenarioSummary}]\n`,
);

async function stopBrowser(browser, devTools) {
  if (devTools) {
    try {
      await devTools.send("Browser.close", {}, 2_000);
    } catch {
      // The socket may close before Chromium acknowledges Browser.close.
    }
    try {
      await devTools.close();
    } catch {
      // Process-group termination below is the authoritative fallback.
    }
  }
  if (!browser?.pid) return;

  const childClosed = waitForChildClose(browser);
  if (!USE_PROCESS_GROUP) {
    await stopWindowsBrowserTree(browser);
  } else {
    if (!(await waitForBrowserTreeExit(browser, 3_000))) {
      signalBrowserTree(browser, "SIGTERM");
    }
    if (!(await waitForBrowserTreeExit(browser, 3_000))) {
      signalBrowserTree(browser, "SIGKILL");
    }
  }
  if (!(await waitForBrowserTreeExit(browser, 2_000))) {
    throw new Error(`O processo do browser ${browser.pid} não encerrou.`);
  }
  await withTimeout(
    childClosed,
    2_000,
    `O processo do browser ${browser.pid} não confirmou o encerramento.`,
  );
}

async function stopWindowsBrowserTree(browser) {
  try {
    const exitCode = await runWindowsTaskkill(browser.pid);
    if (exitCode === 0) return;
    if (await waitForBrowserTreeExit(browser, 250)) return;
    throw new Error(
      `taskkill não encerrou a árvore do browser ${browser.pid} (código ${exitCode}).`,
    );
  } catch (error) {
    if (await waitForBrowserTreeExit(browser, 250)) return;
    try {
      browser.kill("SIGKILL");
    } catch (fallbackError) {
      if (fallbackError?.code !== "ESRCH") throw fallbackError;
    }
    throw error;
  }
}

function runWindowsTaskkill(pid) {
  return new Promise((resolve, reject) => {
    const taskkill = spawn(
      "taskkill",
      ["/PID", String(pid), "/T", "/F"],
      { stdio: "ignore", windowsHide: true },
    );
    let settled = false;
    const timeout = setTimeout(() => {
      if (settled) return;
      settled = true;
      taskkill.kill("SIGKILL");
      reject(new Error(`taskkill excedeu o limite ao encerrar o browser ${pid}.`));
    }, WINDOWS_TASKKILL_TIMEOUT_MS);
    const settle = (error, exitCode) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      if (error) reject(error);
      else resolve(exitCode);
    };
    taskkill.once("error", (error) => settle(error));
    taskkill.once("close", (exitCode) => settle(undefined, exitCode));
  });
}

function waitForChildClose(browser) {
  if (hasChildExited(browser)) return Promise.resolve();
  return new Promise((resolve) => {
    browser.once("close", resolve);
  });
}

function hasChildExited(browser) {
  return browser.exitCode !== null || browser.signalCode !== null;
}

async function waitForBrowserTreeExit(browser, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (browserTreeIsRunning(browser)) {
    if (Date.now() >= deadline) return false;
    await delay(50);
  }
  return true;
}

function browserTreeIsRunning(browser) {
  if (!USE_PROCESS_GROUP) return !hasChildExited(browser);
  try {
    process.kill(-browser.pid, 0);
    return true;
  } catch (error) {
    if (error?.code === "ESRCH") return false;
    if (error?.code === "EPERM") return !hasChildExited(browser);
    throw error;
  }
}

function signalBrowserTree(browser, signal) {
  try {
    if (USE_PROCESS_GROUP) process.kill(-browser.pid, signal);
    else browser.kill(signal);
  } catch (error) {
    if (error?.code === "EPERM" && !hasChildExited(browser)) {
      browser.kill(signal);
      return;
    }
    if (error?.code !== "ESRCH" && error?.code !== "EPERM") throw error;
  }
}

async function removeTemporaryDirectory(directory) {
  let lastError;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      rmSync(directory, {
        recursive: true,
        force: true,
        maxRetries: 3,
        retryDelay: 50,
      });
      await delay(50);
      if (!existsSync(directory)) return;
      lastError = Object.assign(
        new Error(`O diretório temporário ${directory} foi recriado pelo browser.`),
        { code: "ENOTEMPTY" },
      );
    } catch (error) {
      if (!RETRYABLE_CLEANUP_CODES.has(error?.code)) throw error;
      lastError = error;
    }
    await delay(Math.min(50 * (attempt + 1), 500));
  }
  throw lastError;
}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}

function withTimeout(promise, timeoutMs, message) {
  let timeout;
  const timeoutPromise = new Promise((_, reject) => {
    timeout = setTimeout(() => reject(new Error(message)), timeoutMs);
  });
  return Promise.race([promise, timeoutPromise])
    .finally(() => clearTimeout(timeout));
}

async function verifyScenario({ viewport, sidebar }, protocol) {
  const fixture = path.join(
    temporaryDirectory,
    `memory-${viewport}-${sidebar}.html`,
  );
  writeFileSync(fixture, pageFixture(sidebar), "utf8");
  await protocol.send("Emulation.setDeviceMetricsOverride", {
    width: viewport,
    height: 900,
    deviceScaleFactor: 1,
    mobile: viewport <= 620,
  });
  const loaded = protocol.waitFor("Page.loadEventFired");
  await Promise.all([
    loaded,
    protocol.send("Page.navigate", { url: pathToFileURL(fixture).href }),
  ]);
  const evaluation = await protocol.send("Runtime.evaluate", {
    expression: "document.getElementById('geometry-result').textContent",
    returnByValue: true,
  });
  const serialized = evaluation?.result?.value;
  if (typeof serialized !== "string" || serialized.length === 0) {
    throw new Error(`Browser geometry did not report measurements at ${viewport}px.`);
  }
  const measurements = JSON.parse(serialized);
  const overflow = Object.entries(measurements)
    .filter(([, box]) => box.scrollWidth > box.clientWidth)
    .map(([name, box]) => `${name} ${box.scrollWidth}>${box.clientWidth}`);
  if (overflow.length > 0) {
    throw new Error(
      `Memory overflow at viewport ${viewport}px/sidebar ${sidebar}px: ${overflow.join(", ")}`,
    );
  }
  const invalidDesktopToggle = viewport > 900 && (
    measurements.toggle.width < 34 ||
    measurements.toggle.width > 36 ||
    measurements.toggle.height < 34 ||
    measurements.toggle.height > 36
  );
  if (invalidDesktopToggle) {
    throw new Error(
      `Sidebar toggle geometry at ${viewport}px/sidebar ${sidebar}px: ` +
        `${measurements.toggle.width}x${measurements.toggle.height}`,
    );
  }
  if (
    viewport <= 900 &&
    (measurements.toggle.width !== 0 || measurements.toggle.height !== 0)
  ) {
    throw new Error(
      `Sidebar toggle must stay out of the mobile document flow at ${viewport}px.`,
    );
  }
  if (measurements.badge.edgeGap < 12) {
    throw new Error(
      `Memory status badge is ${measurements.badge.edgeGap}px from its surface edge ` +
        `at viewport ${viewport}px/sidebar ${sidebar}px.`,
    );
  }
  if (measurements.status.edgeGap < 12) {
    throw new Error(
      `Operational status is ${measurements.status.edgeGap}px from its surface edge ` +
      `at viewport ${viewport}px/sidebar ${sidebar}px.`,
    );
  }
  if (measurements.status.whiteSpace === "nowrap") {
    throw new Error(
      `Operational status does not wrap at viewport ${viewport}px/sidebar ${sidebar}px.`,
    );
  }
  if (measurements.status.overflowWrap !== "anywhere") {
    throw new Error(
      `Operational status cannot break long content at viewport ${viewport}px/` +
        `sidebar ${sidebar}px.`,
    );
  }
  if (measurements.header.borderBottomWidth !== 1) {
    throw new Error(
      `Header brand line is ${measurements.header.borderBottomWidth}px ` +
        `at viewport ${viewport}px/sidebar ${sidebar}px.`,
    );
  }
}

async function readDevToolsPort(profile, browser) {
  const portFile = path.join(profile, "DevToolsActivePort");
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (browser.exitCode !== null) {
      throw new Error("O browser encerrou antes de iniciar o protocolo de inspeção.");
    }
    if (existsSync(portFile)) {
      const port = Number(readFileSync(portFile, "utf8").split("\n")[0]);
      if (Number.isInteger(port) && port > 0) return port;
    }
    await delay(100);
  }
  browser.kill("SIGTERM");
  throw new Error("O browser não iniciou o protocolo de inspeção em 10 segundos.");
}

async function connectDevTools(url) {
  const socket = new WebSocket(url);
  await withTimeout(
    new Promise((resolve, reject) => {
      socket.addEventListener("open", resolve, { once: true });
      socket.addEventListener("error", reject, { once: true });
    }),
    10_000,
    "O protocolo do browser não conectou em 10 segundos.",
  );
  let sequence = 0;
  const pending = new Map();
  const eventWaiters = new Map();
  const closed = new Promise((resolve) => {
    if (socket.readyState === WebSocket.CLOSED) resolve();
    else socket.addEventListener("close", resolve, { once: true });
  });
  socket.addEventListener("close", () => {
    const error = new Error("O protocolo do browser foi encerrado.");
    for (const request of pending.values()) request.reject(error);
    pending.clear();
    for (const waiters of eventWaiters.values()) {
      for (const waiter of waiters) {
        clearTimeout(waiter.timeout);
        waiter.reject(error);
      }
    }
    eventWaiters.clear();
  });
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(String(event.data));
    if (message.id !== undefined) {
      const request = pending.get(message.id);
      if (!request) return;
      pending.delete(message.id);
      if (message.error) request.reject(new Error(message.error.message));
      else request.resolve(message.result);
      return;
    }
    const waiters = eventWaiters.get(message.method) ?? [];
    eventWaiters.delete(message.method);
    for (const waiter of waiters) {
      clearTimeout(waiter.timeout);
      waiter.resolve(message.params);
    }
  });
  const send = (
    method,
    params = {},
    timeoutMs = CDP_REQUEST_TIMEOUT_MS,
  ) => {
    const id = ++sequence;
    const request = new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      socket.send(JSON.stringify({ id, method, params }));
    });
    return withTimeout(
      request,
      timeoutMs,
      `A requisição CDP ${method} não respondeu em ${timeoutMs} ms.`,
    ).finally(() => pending.delete(id));
  };
  await send("Page.enable");
  return {
    send,
    waitFor(method, timeoutMs = 10_000) {
      return new Promise((resolve, reject) => {
        const waiters = eventWaiters.get(method) ?? [];
        const waiter = {
          resolve,
          reject,
          timeout: setTimeout(() => {
            const activeWaiters = eventWaiters.get(method) ?? [];
            const remainingWaiters = activeWaiters.filter(
              (candidate) => candidate !== waiter,
            );
            if (remainingWaiters.length > 0) {
              eventWaiters.set(method, remainingWaiters);
            } else {
              eventWaiters.delete(method);
            }
            reject(new Error(`O evento ${method} não ocorreu em ${timeoutMs} ms.`));
          }, timeoutMs),
        };
        waiters.push(waiter);
        eventWaiters.set(method, waiters);
      });
    },
    async close() {
      if (socket.readyState === WebSocket.OPEN) socket.close();
      if (socket.readyState !== WebSocket.CLOSED) {
        await withTimeout(
          closed,
          2_000,
          "O protocolo do browser não confirmou o encerramento.",
        );
      }
    },
  };
}

function pageFixture(sidebar) {
  const longId = "evidence-" + "0123456789abcdef".repeat(18);
  const filters = [
    "Obra", "Tipo de evento", "Tipo de entidade", "ID da entidade",
    "ID do RDO", "Origem", "Resultado", "Desde UTC", "Até UTC",
  ].map((label, index) => `
    <label>${label}<input value="filtro-estrutural-${index}-${longId}"></label>
  `).join("");
  return `<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <style>${SHELL_CSS}\n${COMPONENT_SHELL_CSS}\n${HEADER_CSS}\n${WORKSPACE_CSS}\n${MEMORY_CSS}</style>
</head>
<body>
  <div class="cortex-shell" style="--sidebar-width:${sidebar}px">
    <button class="sidebar-toggle" aria-label="Recolher menu">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14.5 6 9 12l5.5 6"></path></svg>
    </button>
    <aside class="cortex-sidebar">Menu</aside>
    <main class="home-dashboard">
      <header class="cortex-page-header">
        <div class="cortex-page-header__copy">
          <p class="cortex-page-header__eyebrow">Memória</p>
          <h1>Registro operacional</h1>
        </div>
      </header>
      <section class="workspace-status-rail">
        <span class="workspace-status-rail__state" data-status="SYNCED">
          <span class="workspace-status-rail__marker"></span>
          <strong>Sincronização operacional confirmada</strong>
          <span>${longId}</span>
        </span>
      </section>
      <section role="tabpanel">
        <div class="memory-ledger">
          <header class="memory-ledger__header">
            <div><h2>Memória operacional</h2><p>Registro ontológico autorizado.</p></div>
            <button class="memory-ledger__refresh">Atualizar Memória</button>
          </header>
          <section class="memory-coverage">
            <span class="memory-coverage__signal"></span>
            <div><strong>Cobertura completa</strong><span>Histórico autorizado disponível.</span></div>
            <dl><div><dt>Marca d'água</dt><dd>Commit 10871</dd></div><div><dt>Cache</dt><dd>349 de 349</dd></div></dl>
          </section>
          <section class="memory-query">
            <label class="memory-query__search">Pesquisa integral<input value="${longId}"></label>
            <details class="memory-query__filters" open>
              <summary>Filtros estruturais</summary>
              <fieldset>${filters}</fieldset>
            </details>
          </section>
          <section class="memory-register">
            <ol class="memory-list">
              <li class="memory-entry" data-status="UPDATED">
                <div class="memory-entry__rail"><span></span></div>
                <div class="memory-entry__commit"><strong>Commit 10871</strong><time>22/07/2026</time></div>
                <div class="memory-entry__body">
                  <div class="memory-entry__title"><h4>RDO editado</h4><span>Atualizado</span></div>
                  <p><strong>RDO 71</strong> · Obra BR-262</p>
                  <dl class="memory-entry__evidence"><div><dt>Evento</dt><dd>${longId}</dd></div></dl>
                </div>
              </li>
            </ol>
          </section>
        </div>
      </section>
    </main>
  </div>
  <pre id="geometry-result"></pre>
  <script>
    const targets = {
      page: document.documentElement,
      shell: document.querySelector('.cortex-shell'),
      dashboard: document.querySelector('.home-dashboard'),
      panel: document.querySelector('[role="tabpanel"]'),
      ledger: document.querySelector('.memory-ledger'),
      query: document.querySelector('.memory-query'),
      filters: document.querySelector('.memory-query__filters'),
      evidence: document.querySelector('.memory-entry__evidence'),
      statusText: document.querySelector('.workspace-status-rail__state > span:last-child')
    };
    const measurements = Object.fromEntries(
      Object.entries(targets).map(([name, element]) => [name, {
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth
      }])
    );
    const toggle = document.querySelector('.sidebar-toggle').getBoundingClientRect();
    const badge = document.querySelector('.memory-entry__title > span').getBoundingClientRect();
    const badgeSurface = document.querySelector('.memory-entry__body').getBoundingClientRect();
    const header = document.querySelector('.cortex-page-header');
    const status = document.querySelector('.workspace-status-rail__state').getBoundingClientRect();
    const statusText = document.querySelector('.workspace-status-rail__state > span:last-child');
    const statusTextStyle = getComputedStyle(statusText);
    const statusSurface = document.querySelector('.workspace-status-rail').getBoundingClientRect();
    measurements.toggle = { width: toggle.width, height: toggle.height };
    measurements.badge = { edgeGap: badgeSurface.right - badge.right };
    measurements.header = {
      borderBottomWidth: Number.parseFloat(getComputedStyle(header).borderBottomWidth)
    };
    measurements.status = {
      edgeGap: Math.min(
        status.left - statusSurface.left,
        statusSurface.right - status.right
      ),
      overflowWrap: statusTextStyle.overflowWrap,
      whiteSpace: statusTextStyle.whiteSpace
    };
    document.getElementById('geometry-result').textContent = JSON.stringify(measurements);
  </script>
</body>
</html>`;
}
