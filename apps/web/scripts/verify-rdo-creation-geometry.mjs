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

if (!BROWSER) {
  throw new Error(
    "CORTEX_BROWSER_BIN é obrigatório para verificar a geometria real do RDO.",
  );
}

const temporaryDirectory = mkdtempSync(
  path.join(os.tmpdir(), "cortex-rdo-creation-geometry-"),
);
let browserProcess;

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
  ], { stdio: "ignore" });
  browserProcess = browser;
  const port = await readDevToolsPort(profile, browser);
  const targets = await fetch(`http://127.0.0.1:${port}/json/list`)
    .then((response) => response.json());
  const page = targets.find((target) => target.type === "page");
  if (!page?.webSocketDebuggerUrl) {
    throw new Error("O browser não expôs uma página para verificar o RDO.");
  }
  const protocol = await connectDevTools(page.webSocketDebuggerUrl);
  const report = [];
  for (const scenario of SCENARIOS) {
    report.push(await verifyScenario(scenario, protocol));
  }
  protocol.close();
  await stopBrowser(browser);
  browserProcess = undefined;
  process.stdout.write(
    `RDO creation geometry verified: ${SCENARIOS.length} scenarios\n`,
  );
  if (process.env.RDO_GEOMETRY_JSON === "1") {
    process.stdout.write(`${JSON.stringify({ scenarios: report }, null, 2)}\n`);
  }
} finally {
  await stopBrowser(browserProcess);
  rmSync(temporaryDirectory, {
    recursive: true,
    force: true,
    maxRetries: 10,
    retryDelay: 100,
  });
}

async function stopBrowser(browser) {
  if (!browser || browser.exitCode !== null) return;
  const exited = new Promise((resolve) => {
    browser.once("exit", resolve);
  });
  browser.kill("SIGTERM");
  await Promise.race([exited, delay(2_000)]);
  if (browser.exitCode === null) {
    browser.kill("SIGKILL");
    await Promise.race([exited, delay(1_000)]);
  }
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
  await protocol.send("Page.navigate", { url: pathToFileURL(fixture).href });
  await loaded;
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
  for (let attempt = 0; attempt < 100; attempt += 1) {
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
  throw new Error("O browser não iniciou o protocolo em 10 segundos.");
}

async function connectDevTools(url) {
  const socket = new WebSocket(url);
  await Promise.race([
    new Promise((resolve, reject) => {
      socket.addEventListener("open", resolve, { once: true });
      socket.addEventListener("error", reject, { once: true });
    }),
    delay(10_000).then(() => {
      throw new Error("O protocolo do browser não conectou em 10 segundos.");
    }),
  ]);
  let sequence = 0;
  const pending = new Map();
  const eventWaiters = new Map();
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
    for (const resolve of waiters) resolve(message.params);
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
        const waiters = eventWaiters.get(method) ?? [];
        waiters.push(resolve);
        eventWaiters.set(method, waiters);
      });
    },
    close() {
      socket.close();
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
