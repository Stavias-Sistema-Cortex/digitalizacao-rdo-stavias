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
const DIALOG_CSS = readFileSync(
  path.join(WEB_ROOT, "src/features/rdos/RdoCreationDialog.css"),
  "utf8",
);
const BROWSER = process.env.CORTEX_BROWSER_BIN ?? [
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/Applications/Chromium.app/Contents/MacOS/Chromium",
].find(existsSync);
const SCENARIOS = [
  { width: 1440, height: 900 },
  { width: 1280, height: 720 },
  { width: 390, height: 844 },
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
  throw new Error(
    "CORTEX_BROWSER_BIN é obrigatório para verificar a geometria real do RDO.",
  );
}

const temporaryDirectory = mkdtempSync(
  path.join(os.tmpdir(), "cortex-rdo-creation-geometry-"),
);
let browserProcess;
let protocol;
let report = [];
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
  })
    .then((response) => response.json());
  const page = targets.find((target) => target.type === "page");
  if (!page?.webSocketDebuggerUrl) {
    throw new Error("O browser não expôs uma página para verificar o RDO.");
  }
  protocol = await connectDevTools(page.webSocketDebuggerUrl);
  for (const scenario of SCENARIOS) {
    report.push(await verifyScenario(scenario, protocol));
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
    "Não foi possível encerrar e limpar o browser de geometria do RDO.",
  );
}

process.stdout.write(
  `RDO creation geometry verified: ${SCENARIOS.length} scenarios\n`,
);
if (process.env.RDO_GEOMETRY_JSON === "1") {
  process.stdout.write(
    `${JSON.stringify({ scenarios: report }, null, 2)}\n`,
  );
}

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

async function verifyScenario({ width, height }, protocol) {
  const fixture = path.join(temporaryDirectory, `rdo-${width}-${height}.html`);
  writeFileSync(fixture, pageFixture(), "utf8");
  await protocol.send("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: width <= 620,
  });
  await protocol.send("Emulation.setEmulatedMedia", {
    media: "screen",
    features: [{ name: "prefers-reduced-motion", value: "reduce" }],
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
    throw new Error(`RDO geometry did not report at ${width}x${height}.`);
  }
  const measurement = JSON.parse(serialized);
  const violations = [];
  if (
    measurement.page.scrollWidth > measurement.page.clientWidth ||
    measurement.page.scrollHeight > measurement.page.clientHeight
  ) {
    violations.push("page overflow");
  }
  if (!measurement.dialogInsideViewport) violations.push("dialog outside viewport");
  for (const [name, visible] of Object.entries(measurement.visible)) {
    if (!visible) violations.push(`${name} is clipped`);
  }
  if (measurement.scrollable.join(",") !== "rdo-creation-worksite-list") {
    violations.push(`scroll owners: ${measurement.scrollable.join(",") || "none"}`);
  }
  if (!measurement.listActuallyScrolls) violations.push("worksite list does not scroll");
  if (!measurement.bodyIsClipped) violations.push("body is not overflow-contained");
  for (const overlap of measurement.overlaps) violations.push(`${overlap} overlaps`);
  if (measurement.animationName !== "none") {
    violations.push(`reduced motion animation=${measurement.animationName}`);
  }
  if (measurement.globalControlInteractiveOverlap) {
    violations.push("global control remains interactive over modal");
  }
  if (violations.length > 0) {
    throw new Error(
      `RDO creation geometry failed at ${width}x${height}: ${violations.join(", ")}`,
    );
  }
  process.stdout.write(
    `${width}x${height}: dialog=${measurement.dialog.width}x${measurement.dialog.height} list=${measurement.list.clientHeight}/${measurement.list.scrollHeight}\n`,
  );
  return { viewport: { width, height }, ...measurement };
}

async function readDevToolsPort(profile, browser) {
  const portFile = path.join(profile, "DevToolsActivePort");
  // O runner compartilhado do CI arranca o Chrome frio e sob disputa de
  // CPU; dez segundos derrubavam o gate por partida lenta, não por defeito
  // de layout. O laço devolve a porta assim que ela aparece, então um teto
  // largo não custa nada no caminho normal — e o processo que morre segue
  // sendo detectado na hora, sem esperar o teto.
  for (let attempt = 0; attempt < 600; attempt += 1) {
    if (browser.exitCode !== null) {
      throw new Error("O browser encerrou antes de iniciar o protocolo.");
    }
    if (existsSync(portFile)) {
      const port = Number(readFileSync(portFile, "utf8").split("\n")[0]);
      if (Number.isInteger(port) && port > 0) return port;
    }
    await delay(100);
  }
  browser.kill("SIGTERM");
  throw new Error("O browser não iniciou o protocolo em 60 segundos.");
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

function pageFixture() {
  const longWorksite =
    "Conservação e recuperação funcional de pavimentos, dispositivos de drenagem e sinalização operacional no corredor rodoviário metropolitano";
  const options = Array.from({ length: 15 }, (_, index) => `
    <label class="rdo-worksite-option${index === 0 ? " rdo-worksite-option--selected" : ""}">
      <input type="radio" name="rdo-worksite" ${index === 0 ? "checked" : ""}>
      <span><strong>${index === 0 ? longWorksite : `Obra autorizada ${index + 1}`}</strong><small>CTR-${String(index + 1).padStart(3, "0")} · Campinas · SP</small></span>
    </label>`).join("");
  return `<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <style>
    * { box-sizing: border-box; }
    html, body { width: 100%; height: 100%; margin: 0; overflow: hidden; font-family: Arial, sans-serif; }
    button, input { min-width: 0; min-height: 40px; font: inherit; }
    label { color: #171b19; font-size: .75rem; font-weight: 600; }
    label > input { display: block; width: 100%; margin-top: 5px; }
    .global-control { position: fixed; right: 6px; bottom: 6px; z-index: 50; width: 44px; height: 44px; }
    ${DIALOG_CSS}
  </style>
</head>
<body>
  <button class="global-control">Global</button>
  <div class="rdo-creation-overlay">
    <div class="rdo-creation-dialog" role="dialog" aria-modal="true">
      <header class="rdo-creation-header">
        <div><p class="rdo-creation-kicker">Córtex · RDO</p><h2>Criar RDO</h2><p>Selecione a obra que dará origem ao relatório.</p></div>
        <button class="rdo-creation-close" aria-label="Fechar">×</button>
      </header>
      <div class="rdo-creation-body">
        <section class="rdo-creation-worksites">
          <label>Buscar obra<input type="search" value=""></label>
          <div class="rdo-selected-worksite" data-selected="true">
            <span>Obra selecionada</span><strong>${longWorksite}</strong><small>CTR-001 · Campinas · SP</small>
          </div>
          <div class="rdo-creation-worksite-list">${options}</div>
        </section>
        <aside class="rdo-provenance-rail">
          <h3>Proveniência</h3>
          <dl>
            <div><dt>Fonte</dt><dd>Cache local</dd></div><div><dt>Contexto</dt><dd>Atualizado</dd></div>
            <div><dt>Versão</dt><dd>Versão 5</dd></div><div><dt>RDO anterior</dt><dd>RDO-0020</dd></div>
            <div><dt>Equipe importada</dt><dd>18 pessoas</dd></div><div><dt>Cobertura</dt><dd>Colaboradores 18/18</dd></div>
          </dl>
        </aside>
      </div>
      <footer class="rdo-creation-footer">
        <label>Data do RDO<input type="date" value="2026-07-22"></label>
        <div class="rdo-creation-actions"><button>Cancelar</button><button class="rdo-creation-primary">Criar rascunho</button></div>
      </footer>
    </div>
  </div>
  <pre id="geometry-result"></pre>
  <script>
    const dialog = document.querySelector('.rdo-creation-dialog');
    const body = document.querySelector('.rdo-creation-body');
    const list = document.querySelector('.rdo-creation-worksite-list');
    const elements = {
      title: document.querySelector('.rdo-creation-header h2'),
      selectedWorksite: document.querySelector('.rdo-selected-worksite'),
      date: document.querySelector('.rdo-creation-footer input'),
      cancel: document.querySelector('.rdo-creation-actions button:first-child'),
      create: document.querySelector('.rdo-creation-actions button:last-child')
    };
    const dialogRect = dialog.getBoundingClientRect();
    const rectangle = (element) => {
      const rect = element.getBoundingClientRect();
      return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: rect.width, height: rect.height };
    };
    const inside = (element) => {
      const rect = element.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0 && rect.left >= dialogRect.left - .5 && rect.right <= dialogRect.right + .5 && rect.top >= dialogRect.top - .5 && rect.bottom <= dialogRect.bottom + .5 && rect.top >= 0 && rect.bottom <= innerHeight;
    };
    const intersects = (left, right) => !(left.right <= right.left || right.right <= left.left || left.bottom <= right.top || right.bottom <= left.top);
    const pairs = [['date', 'cancel'], ['date', 'create'], ['cancel', 'create'], ['selectedWorksite', 'date']];
    const overlaps = pairs.filter(([left, right]) => intersects(rectangle(elements[left]), rectangle(elements[right]))).map(([left, right]) => left + '/' + right);
    const scrollable = [...dialog.querySelectorAll('*')].filter((element) => {
      const overflowY = getComputedStyle(element).overflowY;
      return element.scrollHeight > element.clientHeight + 1 && (overflowY === 'auto' || overflowY === 'scroll');
    }).map((element) => element.className).filter((name) => typeof name === 'string');
    const global = document.querySelector('.global-control').getBoundingClientRect();
    const topAtGlobal = document.elementFromPoint(global.left + global.width / 2, global.top + global.height / 2);
    const measurement = {
      page: { clientWidth: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth, clientHeight: document.documentElement.clientHeight, scrollHeight: document.documentElement.scrollHeight },
      dialog: rectangle(dialog),
      list: { clientHeight: list.clientHeight, scrollHeight: list.scrollHeight },
      dialogInsideViewport: dialogRect.left >= 0 && dialogRect.right <= innerWidth && dialogRect.top >= 0 && dialogRect.bottom <= innerHeight,
      visible: Object.fromEntries(Object.entries(elements).map(([name, element]) => [name, inside(element)])),
      scrollable,
      listActuallyScrolls: list.scrollHeight > list.clientHeight + 1,
      bodyIsClipped: getComputedStyle(body).overflow === 'hidden',
      overlaps,
      animationName: getComputedStyle(dialog).animationName,
      globalControlInteractiveOverlap: Boolean(topAtGlobal?.closest('.global-control'))
    };
    document.getElementById('geometry-result').textContent = JSON.stringify(measurement);
  </script>
</body>
</html>`;
}
