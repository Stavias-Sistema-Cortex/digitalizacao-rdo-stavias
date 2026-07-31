/**
 * Verificação de geometria do Mapa da Rodovia em navegador real.
 *
 * Sobe o harness de `scripts/mapa-rodovia-preview` (componentes reais, dados de
 * fixture), dirige um Chromium headless por CDP e mede a composição nos três
 * viewports exigidos pelo projeto, capturando um PNG de cada.
 *
 *   node scripts/verify-mapa-rodovia-geometry.mjs
 *
 * Requer `CORTEX_BROWSER_BIN` ou um Chromium/Chrome em caminho conhecido.
 */
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const WEB_ROOT = path.resolve(path.dirname(SCRIPT_PATH), "..");
const PREVIEW_CONFIG = path.join(
  WEB_ROOT,
  "scripts/mapa-rodovia-preview/vite.config.ts",
);
/*
 * A saída fica fora da árvore do repositório: o verificador de fronteira varre
 * todo o projeto e um relatório com caminhos absolutos acionaria seus alertas
 * de marca sem que houvesse nada de errado no produto.
 */
const OUTPUT_DIR =
  process.env.CORTEX_GEOMETRY_OUTPUT_DIR ??
  path.join(os.tmpdir(), "cortex-mapa-rodovia-geometry");
const PREVIEW_URL = "http://127.0.0.1:5199/";
const SCENARIOS = [
  { width: 1440, height: 900 },
  { width: 1280, height: 720 },
  { width: 390, height: 844 },
];
const BROWSER =
  process.env.CORTEX_BROWSER_BIN ??
  [
    "/opt/pw-browsers/chromium",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/usr/bin/google-chrome",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
  ].find(existsSync);

if (!BROWSER) {
  throw new Error(
    "CORTEX_BROWSER_BIN é obrigatório para verificar a geometria do Mapa da Rodovia.",
  );
}

async function waitFor(probe, timeoutMs, description) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await probe();
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    await delay(250);
  }
  throw new Error(
    `Timeout aguardando ${description}${lastError ? `: ${lastError.message}` : ""}`,
  );
}

function connect(webSocketUrl) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(webSocketUrl);
    const pending = new Map();
    let nextId = 0;

    socket.addEventListener("open", () =>
      resolve({
        send(method, params = {}, sessionId) {
          const id = (nextId += 1);
          return new Promise((resolveCall, rejectCall) => {
            pending.set(id, { resolveCall, rejectCall });
            socket.send(
              JSON.stringify({ id, method, params, sessionId }),
            );
          });
        },
        close: () => socket.close(),
      }),
    );
    socket.addEventListener("error", () =>
      reject(new Error("Falha ao abrir o canal CDP.")),
    );
    socket.addEventListener("message", (event) => {
      const message = JSON.parse(String(event.data));
      const call = pending.get(message.id);
      if (!call) return;
      pending.delete(message.id);
      if (message.error) {
        call.rejectCall(new Error(message.error.message));
      } else {
        call.resolveCall(message.result);
      }
    });
  });
}

const MEASURE = `(() => {
  const rect = (selector, root = document) => {
    const node = root.querySelector(selector);
    if (!node) return null;
    const box = node.getBoundingClientRect();
    return {
      left: Math.round(box.left), right: Math.round(box.right),
      top: Math.round(box.top), bottom: Math.round(box.bottom),
      width: Math.round(box.width), height: Math.round(box.height),
    };
  };
  const ativa = document.querySelector('[data-cenario="obra-ativa"]');
  const desativada = document.querySelector('[data-cenario="obra-desativada"]');
  const paineis = [...ativa.querySelectorAll('.rodovia-workspace-painel')]
    .map((node) => {
      const box = node.getBoundingClientRect();
      return { left: Math.round(box.left), width: Math.round(box.width) };
    });
  const pistas = [...ativa.querySelectorAll('.trecho-pista')].map((node) => ({
    nome: node.querySelector('.trecho-pista-nome')?.textContent?.trim() ?? null,
    blocos: [...node.querySelectorAll('.trecho-bloco')].map((bloco) => ({
      esquerda: bloco.style.left,
      largura: bloco.style.width,
      estado: [...bloco.classList].find((c) => c.startsWith('trecho-bloco--')) ?? null,
      rotulo: bloco.textContent?.trim() ?? null,
    })),
  }));
  return {
    page: {
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    },
    // O corpo nunca pode rolar horizontalmente.
    semRolagemHorizontal:
      document.documentElement.scrollWidth <= document.documentElement.clientWidth,
    splitLadoALado: paineis.length === 2 && paineis[0].left !== paineis[1].left,
    paineis,
    mapaSatelite: rect('.operational-map', ativa),
    mapaLeaflet: rect('.leaflet-trecho', ativa),
    esquematico: rect('.trecho-esquematico', ativa),
    canteiro: Boolean(ativa.querySelector('.trecho-canteiro')),
    marcosKm: [...ativa.querySelectorAll('.trecho-marco')].map(
      (node) => node.textContent?.replace(/\\s+/g, ' ').trim(),
    ),
    pistas,
    botaoDesenhar: Boolean(
      ativa.querySelector('.rodovia-desenho-botao'),
    ),
    periodo: {
      temCampos: ativa.querySelectorAll('.trecho-periodo-campos input').length,
      dias: [...ativa.querySelectorAll('.trecho-dia')].map(
        (node) => node.textContent?.replace(/\\s+/g, ' ').trim(),
      ),
    },
    desativada: {
      temResumo: Boolean(desativada.querySelector('.trecho-resumo')),
      temMapa: Boolean(desativada.querySelector('.rodovia-workspace')),
      temEsquematico: Boolean(desativada.querySelector('.trecho-pista-quadro')),
      medidas: [...desativada.querySelectorAll('.trecho-resumo-grade dt')].map(
        (node) => node.textContent?.trim(),
      ),
    },
  };
})()`;

let vite;
let browser;
let protocol;

try {
  mkdirSync(OUTPUT_DIR, { recursive: true });

  vite = spawn(
    process.execPath,
    [path.join(WEB_ROOT, "node_modules/vite/bin/vite.js"), "--config", PREVIEW_CONFIG],
    { cwd: WEB_ROOT, stdio: "ignore", detached: process.platform !== "win32" },
  );

  await waitFor(
    async () => (await fetch(PREVIEW_URL)).ok,
    60_000,
    "o servidor do harness",
  );

  browser = spawn(
    BROWSER,
    [
      "--headless=new",
      "--disable-gpu",
      "--no-sandbox",
      "--disable-dev-shm-usage",
      "--force-prefers-reduced-motion",
      "--remote-debugging-port=9333",
      "about:blank",
    ],
    { stdio: "ignore", detached: process.platform !== "win32" },
  );

  const version = await waitFor(
    async () => (await fetch("http://127.0.0.1:9333/json/version")).json(),
    30_000,
    "o Chromium headless",
  );
  protocol = await connect(version.webSocketDebuggerUrl);

  const { targetId } = await protocol.send("Target.createTarget", {
    url: "about:blank",
  });
  const { sessionId } = await protocol.send("Target.attachToTarget", {
    targetId,
    flatten: true,
  });
  await protocol.send("Page.enable", {}, sessionId);
  await protocol.send("Runtime.enable", {}, sessionId);
  await protocol.send(
    "Emulation.setEmulatedMedia",
    { features: [{ name: "prefers-reduced-motion", value: "reduce" }] },
    sessionId,
  );

  const report = [];
  for (const viewport of SCENARIOS) {
    await protocol.send(
      "Emulation.setDeviceMetricsOverride",
      { ...viewport, deviceScaleFactor: 1, mobile: viewport.width < 768 },
      sessionId,
    );
    await protocol.send("Page.navigate", { url: PREVIEW_URL }, sessionId);
    await delay(2_500);

    const measurement = await waitFor(
      async () => {
        const result = await protocol.send(
          "Runtime.evaluate",
          { expression: MEASURE, returnByValue: true },
          sessionId,
        );
        if (result.exceptionDetails) {
          throw new Error(result.exceptionDetails.text);
        }
        return result.result.value;
      },
      20_000,
      `a medição em ${viewport.width}x${viewport.height}`,
    );

    const shot = await protocol.send(
      "Page.captureScreenshot",
      { format: "png", captureBeyondViewport: true },
      sessionId,
    );
    const file = path.join(
      OUTPUT_DIR,
      `mapa-rodovia-${viewport.width}x${viewport.height}.png`,
    );
    writeFileSync(file, Buffer.from(shot.data, "base64"));

    report.push({ viewport, screenshot: file, ...measurement });
  }

  writeFileSync(
    path.join(OUTPUT_DIR, "mapa-rodovia-geometry.json"),
    `${JSON.stringify(report, null, 2)}\n`,
    "utf8",
  );

  const falhas = report.filter(
    (cenario) =>
      !cenario.semRolagemHorizontal ||
      !cenario.esquematico ||
      cenario.desativada.temMapa ||
      !cenario.desativada.temResumo,
  );
  if (falhas.length > 0) {
    throw new Error(
      `Geometria reprovada em: ${falhas
        .map((c) => `${c.viewport.width}x${c.viewport.height}`)
        .join(", ")}`,
    );
  }

  console.log(JSON.stringify(report, null, 2));
} finally {
  protocol?.close();
  for (const child of [browser, vite]) {
    if (!child?.pid) continue;
    try {
      if (process.platform === "win32") child.kill();
      else process.kill(-child.pid, "SIGTERM");
    } catch {
      child.kill("SIGKILL");
    }
  }
}
