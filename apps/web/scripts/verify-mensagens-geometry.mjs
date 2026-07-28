import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const WEB_ROOT = path.resolve(path.dirname(SCRIPT_PATH), "..");
const DEFAULT_CSS_PATH = path.join(
  WEB_ROOT,
  "src/features/mensagens/MensagensPage.css",
);
const DEFAULT_SHELL_CSS_PATH = path.join(
  WEB_ROOT,
  "src/components/shell/CortexShell.css",
);

export const VIEWPORTS = [
  { name: "mobile", width: 390, height: 844 },
  { name: "laptop", width: 1100, height: 800 },
  { name: "wide", width: 1440, height: 900 },
];

function number(css, pattern, label) {
  const match = css.match(pattern);
  if (!match) {
    throw new Error(`Mensagens geometry token missing: ${label}`);
  }
  return Number(match[1]);
}

function requirePattern(css, pattern, label) {
  if (!pattern.test(css)) {
    throw new Error(`Mensagens geometry token missing: ${label}`);
  }
}

export function readGeometryTokens(
  css,
  shellCss = readFileSync(DEFAULT_SHELL_CSS_PATH, "utf8"),
) {
  const twoColumns = css.match(
    /@container \(min-width:\s*(\d+)px\)\s*\{\s*\.mensagens-workspace\s*\{\s*grid-template-columns:\s*minmax\((\d+)px, (\d+)px\) minmax\(0, 1fr\)/,
  );
  const threeColumns = css.match(
    /@container \(min-width:\s*(\d+)px\)\s*\{\s*\.mensagens-workspace\s*\{\s*grid-template-columns:\s*minmax\((\d+)px, (\d+)px\) minmax\(0, 1fr\) (\d+)px/,
  );
  if (!twoColumns || !threeColumns) {
    throw new Error("Mensagens geometry token missing: responsive columns");
  }
  const composerBar = css.match(
    /\.mensagens-composer-bar\s*\{[\s\S]*?gap:\s*(\d+)px;[\s\S]*?padding:\s*(\d+)px\s+(\d+)px\s+(\d+)px\s+(\d+)px;/,
  );
  if (!composerBar) {
    throw new Error("Mensagens geometry token missing: composer bar box");
  }
  const gutter = css.match(
    /--mensagens-gutter:\s*clamp\((\d+)px,\s*[^,]+,\s*(\d+)px\)/,
  );
  if (!gutter) {
    throw new Error("Mensagens geometry token missing: page gutter");
  }
  requirePattern(
    css,
    /\.mensagens-page\s*\{[\s\S]*?min-height:\s*100dvh;[\s\S]*?display:\s*flex;[\s\S]*?flex-direction:\s*column;/,
    "page flex flow",
  );
  requirePattern(
    css,
    /\.mensagens-frame\s*\{[\s\S]*?display:\s*flex;[\s\S]*?flex:\s*1 1 auto;/,
    "frame flex flow",
  );
  requirePattern(
    css,
    /\.mensagens-workspace\s*\{[\s\S]*?min-height:\s*(\d+)rem;[\s\S]*?flex:\s*1 1 auto;/,
    "workspace flex flow",
  );
  return {
    shellBreakpoint: number(
      shellCss,
      /@media \(max-width:\s*(\d+)px\)\s*\{\s*\.cortex-shell,\s*\.cortex-shell--collapsed\s*\{/,
      "shell breakpoint",
    ),
    sidebarWidth: number(
      shellCss,
      /grid-template-columns:\s*var\(--sidebar-width,\s*(\d+)px\) minmax\(0, 1fr\)/,
      "shell sidebar width",
    ),
    pageGutterMinimum: Number(gutter[1]),
    pageGutterMaximum: Number(gutter[2]),
    twoColumnBreakpoint: Number(twoColumns[1]),
    threeColumnBreakpoint: Number(threeColumns[1]),
    listMin: Number(twoColumns[2]),
    listMax: Number(twoColumns[3]),
    contextWidth: Number(threeColumns[4]),
    workspaceMinimumHeight: number(
      css,
      /\.mensagens-workspace\s*\{[\s\S]*?min-height:\s*(\d+)rem;/,
      "workspace minimum height",
    ) * 16,
    composerDesktopPadding: number(
      css,
      /\.mensagens-composer\s*\{\s*padding:\s*12px\s+(\d+)px\s+16px/,
      "composer desktop padding",
    ),
    composerMobilePadding: number(
      css,
      /@media \(max-width:\s*700px\)[\s\S]*?\.mensagens-composer\s*\{\s*padding:\s*10px\s+(\d+)px\s+12px/,
      "composer mobile padding",
    ),
    composerControl: number(
      css,
      /\.mensagens-send\s*\{[\s\S]*?width:\s*(\d+)px;[\s\S]*?height:\s*\1px;/,
      "send control dimensions",
    ),
    composerGap: Number(composerBar[1]),
    composerBarRightPadding: Number(composerBar[3]),
    composerBarLeftPadding: Number(composerBar[5]),
    minimumTextareaWidth: 48,
  };
}

function rectangle(left, width) {
  return { left, right: left + width, width };
}

export function measureMensagensGeometry(viewport, tokens) {
  const mobileShell = viewport.width <= tokens.shellBreakpoint;
  const contentWidth = mobileShell
    ? viewport.width
    : viewport.width - tokens.sidebarWidth;
  const gutter = viewport.width <= 700
    ? tokens.pageGutterMinimum
    : tokens.pageGutterMaximum;
  const frameWidth = contentWidth - gutter * 2;
  const workspaceHeight = tokens.workspaceMinimumHeight;

  let columns;
  if (frameWidth >= tokens.threeColumnBreakpoint) {
    const listWidth = Math.min(
      tokens.listMax,
      frameWidth - tokens.contextWidth,
    );
    columns = [
      rectangle(0, listWidth),
      rectangle(listWidth, frameWidth - listWidth - tokens.contextWidth),
      rectangle(frameWidth - tokens.contextWidth, tokens.contextWidth),
    ];
  } else if (frameWidth >= tokens.twoColumnBreakpoint) {
    const listWidth = Math.min(tokens.listMax, frameWidth);
    columns = [
      rectangle(0, listWidth),
      rectangle(listWidth, frameWidth - listWidth),
    ];
  } else {
    columns = [rectangle(0, frameWidth)];
  }

  const thread = columns.length === 1 ? columns[0] : columns[1];
  const composerPadding = viewport.width <= 700
    ? tokens.composerMobilePadding
    : tokens.composerDesktopPadding;
  const barLeft = thread.left + composerPadding;
  const barWidth = thread.width - 2 * composerPadding;
  const attach = rectangle(
    barLeft + tokens.composerBarLeftPadding,
    tokens.composerControl,
  );
  const send = rectangle(
    barLeft + barWidth - tokens.composerBarRightPadding - tokens.composerControl,
    tokens.composerControl,
  );
  const textarea = rectangle(
    attach.right + tokens.composerGap,
    send.left - tokens.composerGap - (attach.right + tokens.composerGap),
  );

  return {
    viewport,
    frame: rectangle(0, frameWidth),
    workspaceHeight,
    columns,
    controls: { attach, send, textarea },
  };
}

export function geometryViolations(measurement, tokens) {
  const violations = [];
  if (measurement.frame.width <= 0 || measurement.workspaceHeight <= 0) {
    violations.push("workspace has non-positive dimensions");
  }
  for (const [index, column] of measurement.columns.entries()) {
    if (
      column.width <= 0 ||
      column.left < measurement.frame.left ||
      column.right > measurement.frame.right
    ) {
      violations.push(`column ${index} exceeds the frame`);
    }
    const next = measurement.columns[index + 1];
    if (next && column.right > next.left) {
      violations.push(`columns ${index} and ${index + 1} overlap`);
    }
  }
  for (const [name, control] of Object.entries(measurement.controls)) {
    if (
      control.width <= 0 ||
      control.left < measurement.frame.left ||
      control.right > measurement.frame.right
    ) {
      violations.push(`${name} exceeds the frame`);
    }
  }
  if (measurement.controls.textarea.width < tokens.minimumTextareaWidth) {
    violations.push("textarea is narrower than its usable minimum");
  }
  if (
    measurement.controls.attach.right > measurement.controls.textarea.left ||
    measurement.controls.textarea.right > measurement.controls.send.left
  ) {
    violations.push("composer controls overlap");
  }
  return violations;
}

export function verifyMensagensGeometry(css = readFileSync(DEFAULT_CSS_PATH, "utf8")) {
  const tokens = readGeometryTokens(css);
  const measurements = VIEWPORTS.map((viewport) =>
    measureMensagensGeometry(viewport, tokens),
  );
  const violations = measurements.flatMap((measurement) =>
    geometryViolations(measurement, tokens).map(
      (violation) => `${measurement.viewport.name}: ${violation}`,
    ),
  );
  if (violations.length > 0) {
    throw new Error(`Mensagens geometry failed:\n${violations.join("\n")}`);
  }
  return measurements;
}

if (path.resolve(process.argv[1] ?? "") === SCRIPT_PATH) {
  try {
    const measurements = verifyMensagensGeometry();
    for (const measurement of measurements) {
      process.stdout.write(
        `${measurement.viewport.name} ${measurement.viewport.width}x${measurement.viewport.height}: frame=${measurement.frame.width}px height=${measurement.workspaceHeight}px columns=${measurement.columns.map((column) => column.width).join("/")} controls=${Object.values(measurement.controls).map((control) => control.width).join("/")}\n`,
      );
    }
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : error}\n`);
    process.exitCode = 1;
  }
}
