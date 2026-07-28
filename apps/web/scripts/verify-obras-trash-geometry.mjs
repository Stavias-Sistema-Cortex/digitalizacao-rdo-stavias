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

if (!BROWSER) {
  throw new Error("CORTEX_BROWSER_BIN é obrigatório para verificar a Lixeira.");
}

const temporaryDirectory = mkdtempSync(
  path.join(os.tmpdir(), "cortex-obras-trash-"),
);
let browserProcess;

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
  ], { stdio: "ignore" });
  const port = await readDevToolsPort(profile, browserProcess);
  const targets = await fetch(`http://127.0.0.1:${port}/json/list`)
    .then((response) => response.json());
  const page = targets.find((target) => target.type === "page");
  if (!page?.webSocketDebuggerUrl) {
    throw new Error("O browser não expôs uma página de inspeção.");
  }
  const protocol = await connectDevTools(page.webSocketDebuggerUrl);
  for (const scenario of SCENARIOS) {
    await verifyScenario(scenario, protocol);
  }
  protocol.close();
  await stopBrowser(browserProcess);
  browserProcess = undefined;
  process.stdout.write(
    `Obras trash geometry verified: ${SCENARIOS.length} scenarios\n`,
  );
} finally {
  await stopBrowser(browserProcess);
  rmSync(temporaryDirectory, {
    recursive: true,
    force: true,
    maxRetries: 10,
    retryDelay: 100,
  });
}

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
  await protocol.send("Page.navigate", {
    url: pathToFileURL(fixture).href,
  });
  await loaded;
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
  await Promise.race([
    new Promise((resolve, reject) => {
      socket.addEventListener("open", resolve, { once: true });
      socket.addEventListener("error", reject, { once: true });
    }),
    delay(10_000).then(() => {
      throw new Error("O protocolo do browser não conectou.");
    }),
  ]);
  let sequence = 0;
  const pending = new Map();
  const waiters = new Map();
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
    const listeners = waiters.get(message.method) ?? [];
    waiters.delete(message.method);
    for (const resolve of listeners) resolve(message.params);
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
  await send("Page.enable");
  return {
    send,
    waitFor(method) {
      return new Promise((resolve) => {
        const listeners = waiters.get(method) ?? [];
        listeners.push(resolve);
        waiters.set(method, listeners);
      });
    },
    close: () => socket.close(),
  };
}

async function stopBrowser(browser) {
  if (!browser || browser.exitCode !== null) return;
  const exited = new Promise((resolve) => browser.once("exit", resolve));
  browser.kill("SIGTERM");
  await Promise.race([exited, delay(2_000)]);
  if (browser.exitCode === null) browser.kill("SIGKILL");
}
