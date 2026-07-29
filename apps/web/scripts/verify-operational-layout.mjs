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
  "src/components/header/CortexPageHeader.css",
  "src/components/SyncStatusBanner.css",
  "src/components/workspace/OperationalWorkspace.css",
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
      : MUTATION === "right-anchored-popovers"
        ? `
          @media (max-width: 760px) {
            .cortex-page-header__global-controls .sync-chip__popover,
            .cortex-page-header__global-controls .profile-menu {
              right: 0 !important;
              left: auto !important;
            }
          }
        `
    : "";
const SCENARIOS = [
  { viewport: 901, sidebar: 360 },
  { viewport: 1000, sidebar: 360 },
  { viewport: 1100, sidebar: 360 },
  { viewport: 620, sidebar: 360 },
  { viewport: 390, sidebar: 360 },
  { viewport: 375, sidebar: 360 },
  { viewport: 320, sidebar: 360 },
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
    "CORTEX_BROWSER_BIN é obrigatório para verificar o layout operacional.",
  );
}

const temporaryDirectory = mkdtempSync(
  path.join(os.tmpdir(), "cortex-operational-layout-"),
);
let browserProcess;
let protocol;
let executionError;

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
    {
      detached: USE_PROCESS_GROUP,
      stdio: "ignore",
    },
  );
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
    "Não foi possível encerrar e limpar o browser do layout operacional.",
  );
}

process.stdout.write(
  `Operational layout verified: ${SCENARIOS.length} scenarios\n`,
);

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
  for (const [name, overlay] of Object.entries(measurement.overlays)) {
    if (!overlay.visible) {
      violations.push(`${name} não abriu`);
    }
    if (overlay.viewportInset < 12) {
      violations.push(
        `${name} ficou a ${overlay.viewportInset}px da borda da viewport ` +
          `(${overlay.left}..${overlay.right}; gatilho ${overlay.triggerLeft})`,
      );
    }
    if (overlay.overlapsTrigger) {
      violations.push(`${name} sobrepôs o próprio controle`);
    }
    if (overlay.maxFontWeight > 600) {
      violations.push(
        `${name} usa peso tipográfico ${overlay.maxFontWeight}`,
      );
    }
  }
  if (viewport === 320) {
    if (measurement.mobileNav.columnCount !== 4) {
      violations.push(
        `navegação móvel usa ${measurement.mobileNav.columnCount} colunas`,
      );
    }
    if (measurement.mobileNav.mensagensOverflow) {
      violations.push("Mensagens transbordou sua coluna");
    }
    if (measurement.mobileNav.mensagensWrapped) {
      violations.push("Mensagens quebrou em mais de uma linha");
    }
    for (const overlap of measurement.mobileNav.overlaps) {
      violations.push(`sobreposição na navegação: ${overlap}`);
    }
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
    <aside class="cortex-sidebar">
      <div class="sidebar-brand">Menu operacional</div>
      <nav class="sidebar-nav" aria-label="Navegação principal">
        ${["Home", "RDO", "Obras", "Equipes", "Mensagens", "Tarefas", "Financeiro"]
          .map(
            (label) => `
              <button class="sidebar-nav-item" type="button">
                <img src="" alt="">
                <span class="sidebar-label">${label}</span>
              </button>
            `,
          )
          .join("")}
      </nav>
      <div class="sidebar-footer"></div>
    </aside>
    <div class="cortex-shell-content">
      <header class="cortex-page-header">
        <div class="cortex-page-header__copy">
          <p class="cortex-page-header__eyebrow">Operação</p>
          <h1>RDOs</h1>
        </div>
        <div class="cortex-page-header__right">
          <div class="cortex-page-header__actions">
            <button type="button">Adicionar colaborador</button>
          </div>
          <div class="cortex-page-header__global-controls">
            <div role="group" aria-label="Controles globais">
              <div class="sync-chip sync-chip--error">
                <button
                  class="sync-chip__button"
                  type="button"
                  aria-expanded="false"
                  aria-haspopup="dialog"
                >
                  Sync
                </button>
                <div
                  class="sync-chip__popover"
                  role="dialog"
                  aria-label="Estado da sincronização"
                  hidden
                >
                  <div class="sync-chip__header">
                    <span class="sync-chip__header-dot"></span>
                    <strong>Falha na sincronização</strong>
                  </div>
                  <p class="sync-chip__description">
                    Os dados continuam salvos neste dispositivo.
                  </p>
                  <p class="sync-chip__error">${longError}</p>
                  <button class="sync-chip__action" type="button">
                    Sincronizar agora
                  </button>
                </div>
              </div>
              <div class="profile-menu-anchor">
                <button
                  class="avatar-button"
                  type="button"
                  aria-expanded="false"
                  aria-haspopup="dialog"
                >
                  JC
                </button>
                <div
                  class="profile-menu"
                  role="dialog"
                  aria-label="Opções do perfil"
                  hidden
                >
                  <p class="profile-menu-name">João Colaborador</p>
                  <p class="profile-menu-scope">Escopo global (Alfa)</p>
                  <button class="profile-menu-security" type="button">
                    Segurança do dispositivo
                  </button>
                  <button class="profile-menu-logout" type="button">
                    Sair
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </header>
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
      <main class="operational-workspace home-dashboard">
        <section class="operational-workspace__content">
          <div class="home-overview">
            <section class="home-obra-card">
              <div class="home-obra-body">
                <dl class="home-obra-infos">
                  <div><dt>Contrato</dt><dd>QA-20260728</dd></div>
                  <div><dt>Localização</dt><dd>São Paulo/SP · BR-116</dd></div>
                  <div><dt>Observações operacionais</dt><dd>Validação responsiva</dd></div>
                </dl>
                <div class="home-obra-chart">
                  <div class="progress-chart">
                    <div class="progress-chart-controls">
                      <div class="progress-chart-legend" role="group" aria-label="Séries do gráfico">
                        <button class="legend-item"><span class="legend-swatch"></span>Avanço físico</button>
                        <button class="legend-item"><span class="legend-swatch"></span>Receita PDOR vs contrato</button>
                      </div>
                      <div class="progress-chart-periods" role="group" aria-label="Período">
                        <button class="period-pill">3m</button>
                        <button class="period-pill period-pill--active">6m</button>
                        <button class="period-pill">12m</button>
                        <button class="period-pill">Tudo</button>
                      </div>
                    </div>
                    <div class="progress-chart-plot">
                      <svg viewBox="0 0 480 240" role="img"></svg>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          </div>
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
      homeWorkspace: document.querySelector(".home-dashboard"),
      homeCard: document.querySelector(".home-obra-card"),
      homeBody: document.querySelector(".home-obra-body"),
      homeChart: document.querySelector(".home-obra-chart"),
      homePeriods: document.querySelector(".progress-chart-periods"),
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
    const baseMeasurement = {
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
        homeCard: contained(
          ".home-obra-card",
          ".operational-workspace__content",
        ),
        homeChart: contained(
          ".home-obra-chart",
          ".home-obra-card",
        ),
        homePeriods: contained(
          ".progress-chart-periods",
          ".home-obra-chart",
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

    const toggleOverlay = (buttonSelector, overlaySelector) => {
      const button = document.querySelector(buttonSelector);
      const overlay = document.querySelector(overlaySelector);
      button.addEventListener("click", () => {
        overlay.hidden = !overlay.hidden;
        button.setAttribute("aria-expanded", String(!overlay.hidden));
      });
      button.click();
      const overlayRect = rect(overlay);
      const buttonRect = rect(button);
      const fontWeights = [overlay, ...overlay.querySelectorAll("*")]
        .map((element) => Number.parseInt(getComputedStyle(element).fontWeight, 10))
        .filter(Number.isFinite);
      const opened = {
        visible: !overlay.hidden && getComputedStyle(overlay).display !== "none",
        viewportInset: Math.round(
          Math.min(
            overlayRect.left,
            document.documentElement.clientWidth - overlayRect.right,
          ),
        ),
        left: Math.round(overlayRect.left),
        right: Math.round(overlayRect.right),
        triggerLeft: Math.round(buttonRect.left),
        overlapsTrigger: intersects(overlayRect, buttonRect),
        maxFontWeight: Math.max(...fontWeights),
      };
      button.click();
      return opened;
    };

    const nav = document.querySelector(".sidebar-nav");
    const navItems = [...nav.querySelectorAll(".sidebar-nav-item")];
    const mensagensItem = navItems.find(
      (item) => item.textContent.trim() === "Mensagens",
    );
    const mensagensLabel = mensagensItem.querySelector(".sidebar-label");
    const mensagensLabelStyle = getComputedStyle(mensagensLabel);
    const mensagensLineHeight = Number.parseFloat(
      mensagensLabelStyle.lineHeight,
    );
    const mobileNav = {
      columnCount: getComputedStyle(nav).gridTemplateColumns
        .split(" ")
        .filter(Boolean).length,
      mensagensOverflow:
        mensagensItem.scrollWidth > mensagensItem.clientWidth + 1 ||
        mensagensLabel.scrollWidth > mensagensLabel.clientWidth + 1,
      mensagensWrapped:
        Number.isFinite(mensagensLineHeight) &&
        mensagensLabel.getBoundingClientRect().height >
          mensagensLineHeight * 1.25,
      overlaps: overlappingPairs(navItems, "item"),
    };
    const measurement = {
      ...baseMeasurement,
      overlays: {
        sync: toggleOverlay(
          ".sync-chip__button",
          ".sync-chip__popover",
        ),
        profile: toggleOverlay(
          ".avatar-button",
          ".profile-menu",
        ),
      },
      mobileNav,
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
