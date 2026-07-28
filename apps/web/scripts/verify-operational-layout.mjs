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
  "src/components/shell/CortexShell.css",
  "src/components/institutional/institutional.css",
  "src/features/rdos/RdoWorkspacePage.css",
]
  .filter((file) => existsSync(path.join(WEB_ROOT, file)))
  .map((file) => readFileSync(path.join(WEB_ROOT, file), "utf8"))
  .join("\n");
const BROWSER = [
  process.env.CORTEX_BROWSER_BIN,
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/Applications/Chromium.app/Contents/MacOS/Chromium",
  "/usr/bin/google-chrome",
  "/usr/bin/google-chrome-stable",
  "/usr/bin/chromium",
  "/usr/bin/chromium-browser",
]
  .filter(Boolean)
  .find(existsSync);
const MUTATION = process.env.CORTEX_OPERATIONAL_LAYOUT_MUTANT;
const MUTATION_CSS =
  MUTATION === "translate-filter"
    ? `
      @media (max-width: 400px) {
        .rdo-filter-grid label:nth-child(2) {
          transform: translateY(-48px);
        }
      }
    `
    : MUTATION === "tight-insets"
      ? `
        @media (max-width: 620px) {
          .rdo-filter-grid,
          .badge-strip {
            padding: 8px !important;
          }
        }
      `
    : "";
const SCENARIOS = [
  { viewport: 901, sidebar: 360 },
  { viewport: 1000, sidebar: 360 },
  { viewport: 1100, sidebar: 360 },
  { viewport: 620, sidebar: 360 },
  { viewport: 375, sidebar: 360 },
];

if (!BROWSER) {
  throw new Error(
    "CORTEX_BROWSER_BIN é obrigatório para verificar o layout operacional.",
  );
}

const temporaryDirectory = mkdtempSync(
  path.join(os.tmpdir(), "cortex-operational-layout-"),
);
let browserProcess;

try {
  const profile = path.join(temporaryDirectory, "browser-profile");
  browserProcess = spawn(
    BROWSER,
    [
      "--headless=new",
      "--disable-gpu",
      "--disable-extensions",
      "--no-first-run",
      "--allow-file-access-from-files",
      "--remote-debugging-port=0",
      `--user-data-dir=${profile}`,
      "about:blank",
    ],
    { stdio: "ignore" },
  );
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
    `Operational layout verified: ${SCENARIOS.length} scenarios\n`,
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
    `operational-${viewport}-${sidebar}.html`,
  );
  writeFileSync(fixture, fixtureHtml(sidebar), "utf8");
  await protocol.send("Emulation.setDeviceMetricsOverride", {
    width: viewport,
    height: 900,
    deviceScaleFactor: 1,
    mobile: viewport <= 620,
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
  if (typeof serialized !== "string" || serialized.length === 0) {
    throw new Error(`Sem medições operacionais em ${viewport}px.`);
  }

  const measurement = JSON.parse(serialized);
  const violations = [];
  for (const [name, box] of Object.entries(measurement.boxes)) {
    if (box.scrollWidth > box.clientWidth + 1) {
      violations.push(
        `${name} transbordou ${box.scrollWidth}>${box.clientWidth}`,
      );
    }
  }
  for (const [name, contained] of Object.entries(measurement.contained)) {
    if (!contained) {
      violations.push(`${name} saiu do container`);
    }
  }
  for (const overlap of measurement.overlaps) {
    violations.push(`sobreposição: ${overlap}`);
  }
  if (measurement.errorInset < 12) {
    violations.push(`erro ficou a ${measurement.errorInset}px da borda`);
  }
  if (measurement.badgeInset < 12) {
    violations.push(`badge ficou a ${measurement.badgeInset}px da borda`);
  }
  if (viewport <= 620 && measurement.filterContentInset < 12) {
    violations.push(
      `filtros ficaram a ${measurement.filterContentInset}px da borda`,
    );
  }
  if (viewport <= 620 && measurement.statusContentInset < 12) {
    violations.push(
      `status ficou a ${measurement.statusContentInset}px da borda`,
    );
  }
  if (measurement.errorOverflowWrap !== "anywhere") {
    violations.push(
      `erro não quebra em qualquer ponto (${measurement.errorOverflowWrap})`,
    );
  }
  if (measurement.errorWhiteSpace === "nowrap") {
    violations.push("erro mantém white-space nowrap");
  }
  if (measurement.filterCount !== 6) {
    violations.push(`filtros renderizados: ${measurement.filterCount}`);
  }

  if (violations.length > 0) {
    throw new Error(
      `Layout operacional falhou em ${viewport}px/sidebar ${sidebar}px: ` +
        violations.join(", "),
    );
  }
}

function fixtureHtml(sidebar) {
  const longError =
    "clientMutationId-" + "0123456789abcdef".repeat(24);
  const longValue =
    "obra-colaborador-subtrecho-" + "0123456789abcdef".repeat(12);
  const filters = [
    ["Obra", "input"],
    ["Período", "select"],
    ["Status", "select"],
    ["Colaborador", "input"],
    ["Trecho", "input"],
    ["Sync", "select"],
  ]
    .map(([label, control], index) => `
      <label>
        ${label}
        ${
          control === "select"
            ? `<select><option>${longValue}-${index}</option></select>`
            : `<input value="${longValue}-${index}">`
        }
      </label>
    `)
    .join("");

  return `<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <style>${CSS}\n${MUTATION_CSS}</style>
</head>
<body>
  <div class="cortex-shell" style="--sidebar-width:${sidebar}px">
    <aside class="cortex-sidebar">Menu operacional</aside>
    <div class="cortex-shell-content">
      <main class="rdo-dashboard">
        <section class="institutional-sync-state institutional-frame rdo-canonical-sync">
          <p class="institutional-sync-state__error" role="status">
            <span>Falha na sincronização</span>
            <span>1 falha local</span>
            <span>${longError}</span>
          </p>
          <dl class="institutional-sync-state__facts">
            <div><dt>Fila local</dt><dd>0</dd></div>
            <div><dt>Conflitos</dt><dd>0</dd></div>
            <div class="institutional-sync-state__last-sync">
              <dt>Última sincronização</dt>
              <dd>${longValue}</dd>
            </div>
          </dl>
        </section>
        <section class="institutional-sync-state institutional-frame badge-strip">
          <span class="institutional-status" data-state="SYNCED">
            Sincronizado
          </span>
          <dl class="institutional-sync-state__facts">
            <div><dt>Fila local</dt><dd>0</dd></div>
            <div><dt>Conflitos</dt><dd>0</dd></div>
            <div><dt>Última sincronização</dt><dd>Agora</dd></div>
          </dl>
        </section>
        <section class="rdo-filter-region" aria-label="Filtros de RDO">
          <div class="rdo-filter-grid">${filters}</div>
        </section>
      </main>
    </div>
  </div>
  <pre id="geometry-result" hidden></pre>
  <script>
    const rect = (target) =>
      (typeof target === "string" ? document.querySelector(target) : target)
        .getBoundingClientRect();
    const contained = (childSelector, parentSelector) => {
      const child = rect(childSelector);
      const parent = rect(parentSelector);
      return (
        child.left >= parent.left - 1 &&
        child.right <= parent.right + 1 &&
        child.top >= parent.top - 1 &&
        child.bottom <= parent.bottom + 1
      );
    };
    const intersects = (left, right) =>
      left.left < right.right &&
      left.right > right.left &&
      left.top < right.bottom &&
      left.bottom > right.top;
    const overlappingPairs = (elements, prefix) => {
      const overlaps = [];
      for (let leftIndex = 0; leftIndex < elements.length; leftIndex += 1) {
        for (
          let rightIndex = leftIndex + 1;
          rightIndex < elements.length;
          rightIndex += 1
        ) {
          if (intersects(rect(elements[leftIndex]), rect(elements[rightIndex]))) {
            overlaps.push(
              prefix + leftIndex + " sobrepõe " + prefix + rightIndex,
            );
          }
        }
      }
      return overlaps;
    };
    const filterLabels = [...document.querySelectorAll(".rdo-filter-grid label")];
    const filterControls = [
      ...document.querySelectorAll(".rdo-filter-grid :is(input, select)"),
    ];
    const stripOverlaps = [
      ...document.querySelectorAll(".institutional-sync-state"),
    ].flatMap((strip, index) =>
      overlappingPairs([...strip.children], "strip" + index + "-item"),
    );
    const sectionOverlaps = overlappingPairs(
      [
        ...document.querySelectorAll(
          ".rdo-dashboard > :is(.institutional-sync-state, .rdo-filter-region)",
        ),
      ],
      "section",
    );
    const boxes = {
      document: document.documentElement,
      shell: document.querySelector(".cortex-shell"),
      content: document.querySelector(".cortex-shell-content"),
      dashboard: document.querySelector(".rdo-dashboard"),
      errorStrip: document.querySelector(".rdo-canonical-sync"),
      error: document.querySelector(".institutional-sync-state__error"),
      facts: document.querySelector(".rdo-canonical-sync .institutional-sync-state__facts"),
      badgeStrip: document.querySelector(".badge-strip"),
      filterRegion: document.querySelector(".rdo-filter-region"),
      filterGrid: document.querySelector(".rdo-filter-grid"),
      ...Object.fromEntries(
        filterLabels
          .map((element, index) => ["filter" + index, element]),
      ),
    };
    const errorStyle = getComputedStyle(
      document.querySelector(".institutional-sync-state__error"),
    );
    const errorRect = rect(".institutional-sync-state__error");
    const errorStripRect = rect(".rdo-canonical-sync");
    const badgeRect = rect(".badge-strip .institutional-status");
    const badgeStripRect = rect(".badge-strip");
    const bounds = (elements) => {
      const rectangles = elements.map(rect);
      return {
        left: Math.min(...rectangles.map((box) => box.left)),
        right: Math.max(...rectangles.map((box) => box.right)),
        top: Math.min(...rectangles.map((box) => box.top)),
        bottom: Math.max(...rectangles.map((box) => box.bottom)),
      };
    };
    const minimumInset = (content, container) =>
      Math.round(Math.min(
        content.left - container.left,
        container.right - content.right,
        content.top - container.top,
        container.bottom - content.bottom,
      ));
    const filterGridRect = rect(".rdo-filter-grid");
    const filterContentBounds = bounds(filterLabels);
    const statusContentBounds = bounds([
      ...document.querySelector(".badge-strip").children,
    ]);
    const measurement = {
      boxes: Object.fromEntries(
        Object.entries(boxes).map(([name, element]) => [
          name,
          {
            clientWidth: element.clientWidth,
            scrollWidth: element.scrollWidth,
          },
        ]),
      ),
      contained: {
        error: contained(
          ".institutional-sync-state__error",
          ".rdo-canonical-sync",
        ),
        facts: contained(
          ".rdo-canonical-sync .institutional-sync-state__facts",
          ".rdo-canonical-sync",
        ),
        badge: contained(
          ".badge-strip .institutional-status",
          ".badge-strip",
        ),
        filters: contained(".rdo-filter-grid", ".rdo-filter-region"),
        firstFilter: contained(
          ".rdo-filter-grid label:first-child",
          ".rdo-filter-grid",
        ),
        lastFilter: contained(
          ".rdo-filter-grid label:last-child",
          ".rdo-filter-grid",
        ),
        ...Object.fromEntries(
          filterControls.map((element, index) => [
            "control" + index,
            contained(element, element.closest("label")),
          ]),
        ),
      },
      overlaps: [
        ...stripOverlaps,
        ...sectionOverlaps,
        ...overlappingPairs(filterLabels, "filter"),
        ...overlappingPairs(filterControls, "control"),
      ],
      errorInset: Math.round(errorRect.left - errorStripRect.left),
      badgeInset: Math.round(badgeRect.left - badgeStripRect.left),
      filterContentInset: minimumInset(
        filterContentBounds,
        filterGridRect,
      ),
      statusContentInset: minimumInset(
        statusContentBounds,
        badgeStripRect,
      ),
      errorOverflowWrap: errorStyle.overflowWrap,
      errorWhiteSpace: errorStyle.whiteSpace,
      filterCount: document.querySelectorAll(".rdo-filter-grid label").length,
    };
    document.getElementById("geometry-result").textContent =
      JSON.stringify(measurement);
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
  if (browser.exitCode === null) {
    browser.kill("SIGKILL");
    await Promise.race([exited, delay(1_000)]);
  }
}
