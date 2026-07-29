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
const CSS = [
  "src/index.css",
  "src/components/workspace/OperationalWorkspace.css",
  "src/features/obras/gestao/gestaoObras.css",
].map((file) => readFileSync(path.join(WEB_ROOT, file), "utf8")).join("\n");
const BROWSER = process.env.CORTEX_BROWSER_BIN ?? [
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/Applications/Chromium.app/Contents/MacOS/Chromium",
].find(existsSync);
const SCENARIOS = [
  { viewport: 901, sidebar: 360 },
  { viewport: 1000, sidebar: 360 },
  { viewport: 1100, sidebar: 360 },
  { viewport: 620, sidebar: 248 },
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
  throw new Error("CORTEX_BROWSER_BIN é obrigatório para verificar a Lixeira.");
}

const temporaryDirectory = mkdtempSync(
  path.join(os.tmpdir(), "cortex-obras-trash-"),
);
let browserProcess;
let protocol;
let executionError;

try {
  const profile = path.join(temporaryDirectory, "browser-profile");
  browserProcess = spawn(BROWSER, [
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
  const port = await readDevToolsPort(profile, browserProcess);
  const targets = await fetch(`http://127.0.0.1:${port}/json/list`, {
    signal: AbortSignal.timeout(TARGET_DISCOVERY_TIMEOUT_MS),
  })
    .then((response) => response.json());
  const page = targets.find((target) => target.type === "page");
  if (!page?.webSocketDebuggerUrl) {
    throw new Error("O browser não expôs uma página de inspeção.");
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
    "Não foi possível encerrar e limpar o browser da Lixeira.",
  );
}

process.stdout.write(
  `Obras trash geometry verified: ${SCENARIOS.length} scenarios\n`,
);

async function verifyScenario({ viewport, sidebar }, protocol) {
  const fixture = path.join(
    temporaryDirectory,
    `obras-trash-${viewport}-${sidebar}.html`,
  );
  writeFileSync(fixture, fixtureHtml(sidebar), "utf8");
  await protocol.send("Emulation.setDeviceMetricsOverride", {
    width: viewport,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false,
  });
  const loaded = protocol.waitFor("Page.loadEventFired");
  await Promise.all([
    loaded,
    protocol.send("Page.navigate", {
      url: pathToFileURL(fixture).href,
    }),
  ]);
  const evaluation = await protocol.send("Runtime.evaluate", {
    expression: "document.getElementById('geometry-result').textContent",
    returnByValue: true,
  });
  const serialized = evaluation?.result?.value;
  if (!serialized) {
    throw new Error(`Sem medições em ${viewport}px.`);
  }
  const measurements = JSON.parse(serialized);
  const overflow = Object.entries(measurements)
    .filter(([, box]) => box.scrollWidth > box.clientWidth)
    .map(([name, box]) => `${name} ${box.scrollWidth}>${box.clientWidth}`);
  if (overflow.length > 0) {
    throw new Error(
      `Lixeira transbordou em ${viewport}px/sidebar ${sidebar}px: ${overflow.join(", ")}`,
    );
  }
}

function fixtureHtml(sidebar) {
  const longText = "Trecho-" + "0123456789abcdef".repeat(24);
  const rows = Array.from({ length: 4 }, (_, index) => `
    <li>
      <div><strong>Obra arquivada ${index} ${longText}</strong><span class="obras-trash-id">${longText}</span></div>
      <span class="obras-trash-status">INATIVA</span>
      <time>28/07/2026</time>
      <span class="obras-sync-state">Sincronizando</span>
      <button>Restaurar</button>
    </li>
  `).join("");
  return `<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <style>${CSS}</style>
</head>
<body>
  <div class="cortex-shell" style="--sidebar-width:${sidebar}px">
    <aside class="cortex-sidebar">Menu</aside>
    <div class="cortex-shell-content">
      <main class="operational-workspace obras-page">
        <section class="operational-workspace__content">
          <section class="obras-trash">
            <ul class="obras-trash-list">${rows}</ul>
          </section>
        </section>
      </main>
    </div>
  </div>
  <script>
    const targets = {
      document: document.documentElement,
      shell: document.querySelector(".cortex-shell"),
      content: document.querySelector(".cortex-shell-content"),
      workspace: document.querySelector(".operational-workspace"),
      workspaceContent: document.querySelector(".operational-workspace__content"),
      trash: document.querySelector(".obras-trash"),
      list: document.querySelector(".obras-trash-list"),
      ...Object.fromEntries(
        [...document.querySelectorAll(".obras-trash-list li")]
          .map((element, index) => ["row" + index, element]),
      ),
    };
    document.body.insertAdjacentHTML(
      "beforeend",
      '<pre id="geometry-result" hidden></pre>',
    );
    document.getElementById("geometry-result").textContent = JSON.stringify(
      Object.fromEntries(
        Object.entries(targets).map(([name, element]) => [
          name,
          {
            clientWidth: element.clientWidth,
            scrollWidth: element.scrollWidth,
          },
        ]),
      ),
    );
  </script>
</body>
</html>`;
}

async function readDevToolsPort(profile, browser) {
  const portFile = path.join(profile, "DevToolsActivePort");
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (browser.exitCode !== null) {
      throw new Error("O browser encerrou antes de iniciar.");
    }
    if (existsSync(portFile)) {
      const port = Number(readFileSync(portFile, "utf8").split("\n")[0]);
      if (Number.isInteger(port) && port > 0) return port;
    }
    await delay(100);
  }
  throw new Error("O browser não iniciou em 10 segundos.");
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

async function stopBrowser(browser, devTools) {
  if (!USE_PROCESS_GROUP) {
    const childClosed = browser?.pid
      ? waitForChildClose(browser)
      : Promise.resolve();
    let shutdownError;
    try {
      if (browser?.pid) await stopWindowsBrowserTree(browser);
    } catch (error) {
      shutdownError = error;
    }
    await closeDevTools(devTools, false);
    if (shutdownError) throw shutdownError;
    if (!browser?.pid) return;
    if (!(await waitForBrowserTreeExit(browser, 2_000))) {
      throw new Error(`O processo do browser ${browser.pid} não encerrou.`);
    }
    await withTimeout(
      childClosed,
      2_000,
      `O processo do browser ${browser.pid} não confirmou o encerramento.`,
    );
    return;
  }

  await closeDevTools(devTools, true);
  if (!browser?.pid) return;

  const childClosed = waitForChildClose(browser);
  if (!(await waitForBrowserTreeExit(browser, 3_000))) {
    signalBrowserTree(browser, "SIGTERM");
  }
  if (!(await waitForBrowserTreeExit(browser, 3_000))) {
    signalBrowserTree(browser, "SIGKILL");
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

async function closeDevTools(devTools, requestBrowserClose) {
  if (!devTools) return;
  if (requestBrowserClose) {
    try {
      await devTools.send("Browser.close", {}, 2_000);
    } catch {
      // The socket may close before Chromium acknowledges Browser.close.
    }
  }
  try {
    await devTools.close();
  } catch {
    // Process-tree termination is the authoritative shutdown boundary.
  }
}

async function stopWindowsBrowserTree(browser) {
  const exitCode = await runWindowsTaskkill(browser.pid);
  if (exitCode !== 0) {
    throw new Error(
      `taskkill não encerrou a árvore do browser ${browser.pid} (código ${exitCode}).`,
    );
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
