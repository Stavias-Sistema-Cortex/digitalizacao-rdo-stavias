# Mensagens Three-Column Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Mensagens tab as the three-column chat layout described in `docs/superpowers/specs/2026-07-21-mensagens-tres-colunas-design.md`, keeping Cortex brand colors and every existing offline-first behavior.

**Architecture:** Split `MensagensPage.tsx` (1247 lines) into an orchestration page plus one component per pane, moving all pure formatting into a tested `mensagensFormat.ts`. The structural split lands first with no behavior change and a green suite, then the visual redesign lands pane by pane. Layout responsiveness keys off a CSS container query on a wrapper element, not viewport media queries, because the shell sidebar is user-resizable. No API, schema, sync, or repository change other than one new field on `ConversationPreview`.

**Tech Stack:** React 19, TypeScript 6, CSS, Vite 8, Vitest 4 (node environment, pure-function tests only — there is no jsdom or React Testing Library in this repo), Playwright 1.61 via `npx` with bundled Chromium.

## Global Constraints

- Portuguese (pt-BR) for every user-visible string, code comment, and commit message. Identifiers stay in the existing mixed convention of each file.
- Palette: teal `#124e4a` (`--color-brand-teal`), action yellow `#fed203`, text `#18231f`, border `--color-border`, muted `--color-muted`. Own bubbles `#d9ece6` with dark text; incoming bubbles `#f4f6f5` with a hairline border.
- Do not add any dependency. No UI framework, no icon package, no date library, no state manager.
- Do not change `mensagensRepository.ts`, `mensagensQueue.ts`, `mensagensApi.ts`, `mensagensHydration.ts`, `objectUploadSync.ts`, the IndexedDB schema, routes, auth, or the sync engine. The single exception is the new `authorId` field on `ConversationPreview` in `mensagensView.ts`.
- No dark mode. No `prefers-color-scheme: dark` block in `MensagensPage.css`.
- Never use `Intl.RelativeTimeFormat` — pt-BR ICU output differs between Node and browsers and does not match the specified labels.
- Commit messages: no `Co-Authored-By` trailer.
- Never `git add -A` or `git commit -a`. `.vscode/settings.json` has unrelated staged and unstaged changes that must stay out of every commit in this plan. Always list paths explicitly.
- Verification commands run from `/Users/joaolucas/digitalizacao-rdo-stavias/apps/web`.

## File Structure

| File | Responsibility |
|---|---|
| `src/features/mensagens/MensagensPage.tsx` | State, effects, data loading, orchestration. Owns every call to the repository, sync, and API. |
| `src/features/mensagens/mensagensFormat.ts` | Pure display formatting: relative time, clock, file size, initials. No React, no IO. |
| `src/features/mensagens/mensagensView.ts` | Pure view derivation: timeline runs, conversation previews, preview labels. Already exists; gains fields. |
| `src/features/mensagens/components/ConversationsPane.tsx` | Left column: search field, conversation rows, search results. |
| `src/features/mensagens/components/MessageThread.tsx` | Center column: thread header, message runs, bubbles, empty state. |
| `src/features/mensagens/components/MessageComposer.tsx` | Center column footer: composer bar, file inputs, offline hint. |
| `src/features/mensagens/components/ConversationInfoPane.tsx` | Right column: identity block plus three collapsible sections. |
| `src/features/mensagens/components/CreateConversationDialog.tsx` | New-conversation modal. Moved unchanged. |
| `src/features/mensagens/components/icons.tsx` | All inline SVG icon components. |
| `src/features/mensagens/MensagensPage.css` | All styles for the feature. Stays a single sectioned file. |
| `src/features/mensagens/mensagensLayout.test.ts` | CSS contract test for the load-bearing layout rules. |

Pane components receive data and callbacks through props and never import the repository, the API, or the sync engine.

---

### Task 1: Commit the WhatsApp baseline and capture reference screenshots

The working tree carries an uncommitted two-column redesign of this page. It has to become a commit of its own so the three-column work is reviewable on top of it, and so it cannot be lost during the refactor.

**Files:**
- Commit (already modified, do not edit): `src/features/mensagens/MensagensPage.tsx`, `src/features/mensagens/MensagensPage.css`
- Create (scratchpad, not committed): `/private/tmp/claude-501/-Users-joaolucas-digitalizacao-rdo-stavias/702f330c-fcc8-4fa9-9715-486648955e66/scratchpad/shot.mjs`

**Interfaces:**
- Consumes: nothing.
- Produces: `shot.mjs`, a Playwright script reused by Tasks 6–11 to screenshot the page with stubbed API routes. Invocation: `node shot.mjs <label> [width] [--info-collapsed]`. Output: `shots/<label>-<width>.png` next to the script.

- [ ] **Step 1: Confirm the current tree is green before freezing it**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npm test
```

Expected: all test files pass. If anything fails, stop and report — the baseline must be green before it is committed.

- [ ] **Step 2: Typecheck and lint the baseline**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npx tsc -b && npm run lint
```

Expected: no output from `tsc`, no errors from `eslint`.

- [ ] **Step 3: Commit the two Mensagens files only**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git commit -m "feat(web): redesenha a aba Mensagens em duas colunas" -- \
  apps/web/src/features/mensagens/MensagensPage.tsx \
  apps/web/src/features/mensagens/MensagensPage.css
git status --short
```

Expected: the commit succeeds and `git status --short` still shows `MM .vscode/settings.json` untouched.

- [ ] **Step 4: Write the screenshot script**

Create `shot.mjs` in the scratchpad directory named above:

```js
import { chromium } from "playwright";
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const label = process.argv[2] ?? "mensagens";
const width = Number(process.argv[3] ?? 1440);
const collapsed = process.argv.includes("--info-collapsed");

const PERFIL = {
  colaboradorId: "11111111-1111-4111-8111-111111111111",
  nome: "Ana Ribeiro",
  papelAcesso: "ALFA",
  escopoGlobal: true,
  obraIds: [],
  expiraEm: "2030-01-01T00:00:00.000Z",
};

const EU = PERFIL.colaboradorId;
const OUTRO = "22222222-2222-4222-8222-222222222222";

function participante(id, nome, papel) {
  return {
    colaboradorId: id,
    nome,
    papel,
    status: "ATIVO",
    adicionadoEm: "2026-07-01T12:00:00.000Z",
  };
}

const CONVERSAS = [
  {
    id: "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa",
    tipo: "OBRA",
    titulo: "Obra Rodovia Vila Nova — Trecho 3",
    obraId: "bbbbbbbb-1111-4111-8111-bbbbbbbbbbbb",
    equipeId: null,
    status: "ATIVA",
    criadaEm: "2026-07-01T12:00:00.000Z",
    atualizadaEm: "2026-07-21T14:40:00.000Z",
    versao: 1,
    participantes: [
      participante(EU, "Ana Ribeiro", "ADMIN"),
      participante(OUTRO, "João Souza", "MEMBRO"),
      participante("33333333-3333-4333-8333-333333333333", "Carla Menezes", "MEMBRO"),
      participante("44444444-4444-4444-8444-444444444444", "Roberto Lima", "MEMBRO"),
    ],
  },
  {
    id: "aaaaaaaa-2222-4222-8222-aaaaaaaaaaaa",
    tipo: "DIRETA",
    titulo: null,
    obraId: null,
    equipeId: null,
    status: "ATIVA",
    criadaEm: "2026-07-02T12:00:00.000Z",
    atualizadaEm: "2026-07-21T11:10:00.000Z",
    versao: 1,
    participantes: [
      participante(EU, "Ana Ribeiro", "ADMIN"),
      participante(OUTRO, "João Souza", "MEMBRO"),
    ],
  },
];

function mensagem(id, autorId, autorNome, corpo, criadaEm, anexos = []) {
  return {
    id,
    conversaId: CONVERSAS[0].id,
    autorId,
    autorNome,
    corpo,
    status: "ATIVA",
    clientMutationId: id,
    criadaNoClienteEm: criadaEm,
    criadaEm,
    editadaEm: null,
    deletadaEm: null,
    versao: 1,
    anexos,
  };
}

// Relative to "now" so the captions exercise every bucket.
const agora = Date.now();
const min = (n) => new Date(agora - n * 60_000).toISOString();

const HISTORICO = [
  mensagem("m1", OUTRO, "João Souza", "Bom dia, Ana. Concretagem do bloco B liberada?", min(400)),
  mensagem("m2", EU, "Ana Ribeiro", "Bom dia! Liberada sim, pode seguir com a equipe.", min(398)),
  mensagem("m3", EU, "Ana Ribeiro", "Só confirma o slump antes de lançar.", min(397)),
  mensagem("m4", OUTRO, "João Souza", "Fechado. Segue o relatório do ensaio.", min(95), [
    {
      id: "anexo-1",
      objetoId: "obj-1",
      nome: "ensaio-slump-bloco-b.pdf",
      mediaType: "application/pdf",
      tamanhoBytes: 284_000,
      sha256: "0".repeat(64),
      ordem: 0,
    },
  ]),
  mensagem("m5", OUTRO, "João Souza", "Chegou 7 cm, dentro da faixa.", min(94)),
  mensagem("m6", EU, "Ana Ribeiro", "Perfeito. Registra no RDO de hoje.", min(12)),
  mensagem("m7", OUTRO, "João Souza", "Registrado.", min(2)),
];

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width, height: 1000 } });

// Pathname predicate, not a glob: "**/api/**" also matches Vite source
// modules such as /src/lib/api/apiEndpoint.ts, which then get served as
// JSON and break the module graph — the page renders blank.
await page.route(
  (url) => url.pathname.startsWith("/api/"),
  async (route) => {
    const url = route.request().url();
    const json = (body) =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (url.includes("/auth/session")) return json(PERFIL);
    if (url.includes("/mensagens/conversas/")) return json(HISTORICO);
    if (url.includes("/mensagens/conversas")) return json(CONVERSAS);
    if (url.includes("/mensagens/busca")) return json([]);
    return json([]);
  },
);

await page.addInitScript((isCollapsed) => {
  localStorage.setItem("cortex.ui.mensagensContextoRecolhido", isCollapsed ? "1" : "0");
}, collapsed);

await page.goto("http://127.0.0.1:5174/mensagens", { waitUntil: "networkidle" });
await page.waitForTimeout(1200);

mkdirSync(join(here, "shots"), { recursive: true });
const file = join(here, "shots", `${label}-${width}.png`);
await page.screenshot({ path: file, fullPage: false });
console.log(file);
await browser.close();
```

- [ ] **Step 5: Start a dev server for THIS worktree on port 5174**

Port 5173 is already held by a Vite server running out of
`.worktrees/cortex-2-1-sync-transport/apps/web`. Screenshotting 5173 silently
captures unrelated code. This plan uses 5174, and Playwright needs `playwright`
resolvable from the scratchpad.

```bash
ln -sfn "$(ls -d /Users/joaolucas/.npm/_npx/*/node_modules/playwright | head -1 | xargs dirname)" \
  /private/tmp/claude-501/-Users-joaolucas-digitalizacao-rdo-stavias/702f330c-fcc8-4fa9-9715-486648955e66/scratchpad/node_modules
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npm run dev -- --port 5174 --host 127.0.0.1
```

Run the server in the background, then confirm:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:5174/
```

Expected: `200`. Confirm the served code is this worktree's by checking that the
first screenshot shows the two-column baseline with dark teal own-bubbles.

- [ ] **Step 6: Capture the baseline**

```bash
cd /private/tmp/claude-501/-Users-joaolucas-digitalizacao-rdo-stavias/702f330c-fcc8-4fa9-9715-486648955e66/scratchpad
node shot.mjs baseline 1440 && node shot.mjs baseline 390
```

Expected: two PNG paths printed. Open both and confirm the two-column layout renders with the fixture conversations. These are the before-images for every later comparison.

---

### Task 2: `mensagensFormat.ts` with relative-time formatting

**Files:**
- Create: `src/features/mensagens/mensagensFormat.ts`
- Create: `src/features/mensagens/mensagensFormat.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `formatRelativeTime(value: string, now: Date): string`
  - `formatClock(value: string): string`
  - `formatFileSize(bytes: number): string`
  - `initials(name: string): string`

- [ ] **Step 1: Write the failing test**

Create `src/features/mensagens/mensagensFormat.test.ts`:

```ts
import { describe, expect, it } from "vitest";

import {
  formatClock,
  formatFileSize,
  formatRelativeTime,
  initials,
} from "./mensagensFormat";

/** Constrói um ISO a partir do fuso local para o teste não depender de TZ. */
function localIso(
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number,
): string {
  return new Date(year, month - 1, day, hour, minute).toISOString();
}

const agora = new Date(2026, 6, 21, 14, 0);

describe("formatRelativeTime", () => {
  it("mostra 'agora' abaixo de um minuto", () => {
    expect(formatRelativeTime(localIso(2026, 7, 21, 13, 59), agora)).toBe("agora");
  });

  it("conta minutos na primeira hora", () => {
    expect(formatRelativeTime(localIso(2026, 7, 21, 13, 55), agora)).toBe("há 5 min");
  });

  it("conta horas no mesmo dia", () => {
    expect(formatRelativeTime(localIso(2026, 7, 21, 11, 0), agora)).toBe("há 3 h");
  });

  it("nomeia o dia anterior", () => {
    expect(formatRelativeTime(localIso(2026, 7, 20, 14, 32), agora)).toBe("ontem 14:32");
  });

  it("usa data curta a partir de dois dias", () => {
    expect(formatRelativeTime(localIso(2026, 7, 12, 14, 32), agora)).toBe("12/07 14:32");
  });

  it("trata relógio adiantado como agora", () => {
    expect(formatRelativeTime(localIso(2026, 7, 21, 14, 30), agora)).toBe("agora");
  });

  it("devolve vazio para data inválida", () => {
    expect(formatRelativeTime("não é data", agora)).toBe("");
  });
});

describe("formatClock", () => {
  it("formata hora e minuto", () => {
    expect(formatClock(localIso(2026, 7, 21, 9, 5))).toBe("09:05");
  });
});

describe("formatFileSize", () => {
  it("usa KB abaixo de um megabyte", () => {
    expect(formatFileSize(284_000)).toBe("277 KB");
  });

  it("nunca mostra zero KB", () => {
    expect(formatFileSize(10)).toBe("1 KB");
  });

  it("usa MB com uma casa a partir de um megabyte", () => {
    expect(formatFileSize(3_500_000)).toBe("3.3 MB");
  });
});

describe("initials", () => {
  it("usa a primeira letra do primeiro e do último nome", () => {
    expect(initials("Ana Ribeiro")).toBe("AR");
  });

  it("usa uma letra quando há só um nome", () => {
    expect(initials("Ana")).toBe("A");
  });

  it("devolve interrogação para nome vazio", () => {
    expect(initials("   ")).toBe("?");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npx vitest run src/features/mensagens/mensagensFormat.test.ts
```

Expected: FAIL — `Failed to resolve import "./mensagensFormat"`.

- [ ] **Step 3: Write the implementation**

Create `src/features/mensagens/mensagensFormat.ts`:

```ts
const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

const clockFormat = new Intl.DateTimeFormat("pt-BR", {
  hour: "2-digit",
  minute: "2-digit",
});

const dayMonthFormat = new Intl.DateTimeFormat("pt-BR", {
  day: "2-digit",
  month: "2-digit",
});

const dayKeyFormat = new Intl.DateTimeFormat("pt-BR", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/**
 * Rótulo relativo compacto das legendas de run.
 * Montado à mão de propósito: o ICU do Node e o do navegador divergem em pt-BR.
 */
export function formatRelativeTime(value: string, now: Date): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  const elapsed = now.getTime() - date.getTime();
  if (elapsed < MINUTE_MS) {
    return "agora";
  }
  if (elapsed < HOUR_MS) {
    return `há ${Math.floor(elapsed / MINUTE_MS)} min`;
  }
  if (elapsed < DAY_MS) {
    return `há ${Math.floor(elapsed / HOUR_MS)} h`;
  }
  if (isPreviousDay(date, now)) {
    return `ontem ${clockFormat.format(date)}`;
  }
  return `${dayMonthFormat.format(date)} ${clockFormat.format(date)}`;
}

export function formatClock(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : clockFormat.format(date);
}

export function formatFileSize(bytes: number): string {
  return bytes < 1024 * 1024
    ? `${Math.max(1, Math.round(bytes / 1024))} KB`
    : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return "?";
  }
  const first = parts[0][0] ?? "";
  const last = parts.length > 1 ? parts[parts.length - 1][0] : "";
  return `${first}${last}`.toLocaleUpperCase("pt-BR");
}

function isPreviousDay(date: Date, now: Date): boolean {
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  return dayKeyFormat.format(date) === dayKeyFormat.format(yesterday);
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npx vitest run src/features/mensagens/mensagensFormat.test.ts
```

Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git commit -m "feat(web): extrai formatadores de Mensagens com tempo relativo" -- \
  apps/web/src/features/mensagens/mensagensFormat.ts \
  apps/web/src/features/mensagens/mensagensFormat.test.ts
```

---

### Task 3: Runs by time gap, `authorId` on previews, preview helpers

**Files:**
- Modify: `src/features/mensagens/mensagensView.ts`
- Modify: `src/features/mensagens/mensagensView.test.ts`
- Modify: `src/features/mensagens/MensagensPage.tsx` (rename at the single call site)

**Interfaces:**
- Consumes: nothing from Task 2.
- Produces:
  - `MessageTimelineEntry` message variant is now `{ kind: "message"; key: string; message: T; startsRun: boolean }` — the field `showAuthor` is gone.
  - `ConversationPreview` gains `authorId: string`.
  - `previewLabel(preview: ConversationPreview | undefined, currentUserId: string, fallback: string): string`
  - `hasPendingMessage(preview: ConversationPreview | undefined): boolean`

- [ ] **Step 1: Write the failing tests**

Append inside the existing `describe("mensagensView", ...)` block in `src/features/mensagens/mensagensView.test.ts`, and add the two new imports (`previewLabel`, `hasPendingMessage`) to the existing import from `./mensagensView`:

```ts
  it("abre novo run quando o mesmo autor volta depois de 15 minutos", () => {
    const entries = buildMessageTimeline([
      message("m1", "c1", "a", "Primeira", "2026-07-14T12:00:00.000Z"),
      message("m2", "c1", "a", "Logo depois", "2026-07-14T12:10:00.000Z"),
      message("m3", "c1", "a", "Bem depois", "2026-07-14T12:40:00.000Z"),
    ]);

    const runs = entries
      .filter((entry) => entry.kind === "message")
      .map((entry) => (entry.kind === "message" ? entry.startsRun : false));

    expect(runs).toEqual([true, false, true]);
  });

  it("guarda o autor da prévia para identificar mensagens próprias", () => {
    const previews = buildConversationPreviews(
      [message("m1", "c1", "a", "Combinado", "2026-07-14T12:00:00.000Z")],
      new Set(),
    );

    expect(previews.c1.authorId).toBe("a");
    expect(previewLabel(previews.c1, "a", "Conversa")).toBe("Você: Combinado");
    expect(previewLabel(previews.c1, "b", "Conversa")).toBe("Combinado");
    expect(previewLabel(undefined, "a", "Conversa")).toBe("Conversa");
  });

  it("sinaliza prévia ainda não sincronizada", () => {
    const previews = buildConversationPreviews(
      [message("m1", "c1", "a", "Enviando", "2026-07-14T12:00:00.000Z")],
      new Set(),
    );

    expect(hasPendingMessage(previews.c1)).toBe(false);
    expect(hasPendingMessage({ ...previews.c1, syncStatus: "NA_FILA" })).toBe(true);
    expect(hasPendingMessage({ ...previews.c1, syncStatus: "FALHOU" })).toBe(true);
    expect(hasPendingMessage(undefined)).toBe(false);
  });
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npx vitest run src/features/mensagens/mensagensView.test.ts
```

Expected: FAIL — `previewLabel` and `hasPendingMessage` are not exported, and `startsRun` does not exist on the entry type.

- [ ] **Step 3: Update `mensagensView.ts`**

Replace the `ConversationPreview` interface, the `MessageTimelineEntry` type, and the `buildMessageTimeline` and `buildConversationPreviews` functions with:

```ts
export interface ConversationPreview {
  messageId: string;
  text: string;
  authorId: string;
  authorName: string;
  at: string;
  syncStatus: MensagemLocalRecord["syncStatus"];
}

export type MessageTimelineEntry<T extends MensagemLocalRecord> =
  | { kind: "date"; key: string; label: string }
  | { kind: "message"; key: string; message: T; startsRun: boolean };

/** Mesmo autor depois desta pausa começa um novo run, como em apps de chat. */
const RUN_GAP_MS = 15 * 60 * 1000;

export function buildMessageTimeline<T extends MensagemLocalRecord>(
  messages: T[],
): MessageTimelineEntry<T>[] {
  const result: MessageTimelineEntry<T>[] = [];
  let previousDate = "";
  let previousAuthor = "";
  let previousAt = Number.NaN;
  for (const message of messages) {
    const date = localDateKey(message.criadaNoClienteEm);
    const at = new Date(message.criadaNoClienteEm).getTime();
    if (date !== previousDate) {
      result.push({
        kind: "date",
        key: `date:${date}`,
        label: formatDateLabel(message.criadaNoClienteEm),
      });
      previousDate = date;
      previousAuthor = "";
      previousAt = Number.NaN;
    }
    const gap =
      Number.isNaN(previousAt) || Number.isNaN(at)
        ? Number.POSITIVE_INFINITY
        : at - previousAt;
    result.push({
      kind: "message",
      key: message.id,
      message,
      startsRun: message.autorId !== previousAuthor || gap > RUN_GAP_MS,
    });
    previousAuthor = message.autorId;
    previousAt = at;
  }
  return result;
}

export function buildConversationPreviews(
  messages: MensagemLocalRecord[],
  messageIdsWithAttachments: Set<string>,
): Record<string, ConversationPreview> {
  const previews: Record<string, ConversationPreview> = {};
  for (const message of [...messages].sort((left, right) =>
    left.criadaNoClienteEm.localeCompare(right.criadaNoClienteEm),
  )) {
    previews[message.conversaId] = {
      messageId: message.id,
      text: previewText(message, messageIdsWithAttachments.has(message.id)),
      authorId: message.autorId,
      authorName: message.autorNome,
      at: message.criadaNoClienteEm,
      syncStatus: message.syncStatus,
    };
  }
  return previews;
}

/** Prefixa "Você:" comparando por id — nomes homônimos enganariam. */
export function previewLabel(
  preview: ConversationPreview | undefined,
  currentUserId: string,
  fallback: string,
): string {
  if (!preview) {
    return fallback;
  }
  return preview.authorId === currentUserId
    ? `Você: ${preview.text}`
    : preview.text;
}

export function hasPendingMessage(
  preview: ConversationPreview | undefined,
): boolean {
  return preview !== undefined && preview.syncStatus !== "SINCRONIZADO";
}
```

- [ ] **Step 4: Update the single call site in `MensagensPage.tsx`**

In the `timeline.map(...)` block, rename the prop passed to `MessageItem` from `showAuthor={entry.showAuthor}` to `showAuthor={entry.startsRun}`. The `MessageItem` signature stays as it is for now — Task 7 reshapes it.

- [ ] **Step 5: Run the full suite to verify it passes**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npm test && npx tsc -b
```

Expected: all tests pass, `tsc` silent.

- [ ] **Step 6: Commit**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git commit -m "feat(web): quebra runs de mensagem por intervalo e marca previas proprias" -- \
  apps/web/src/features/mensagens/mensagensView.ts \
  apps/web/src/features/mensagens/mensagensView.test.ts \
  apps/web/src/features/mensagens/MensagensPage.tsx
```

---

### Task 4: Extract icons and the new-conversation dialog

Pure moves. No line of moved code changes except added imports and exports.

**Files:**
- Create: `src/features/mensagens/components/icons.tsx`
- Create: `src/features/mensagens/components/CreateConversationDialog.tsx`
- Modify: `src/features/mensagens/MensagensPage.tsx`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `icons.tsx` exports `IconProps`, `IconPaperclip`, `IconSend`, `IconSpinner`, `IconInfo`, `IconChevronLeft`, `IconClose`, `IconFile`, `IconClock`, `IconCheckDouble`, `IconWarning`.
  - `CreateConversationDialog.tsx` default-exports nothing and named-exports `CreateConversationDialog` with its current props object: `{ obrasPromise: Promise<ObraLocalRecord[]>; alfa: boolean; onClose: () => void; onCreated: (conversation) => Promise<void> }`.

- [ ] **Step 1: Move the icon components**

Create `src/features/mensagens/components/icons.tsx` containing, verbatim, the `type IconProps` declaration and the eleven `Icon*` function components currently at the bottom of `MensagensPage.tsx` (lines 1072–1247). Add `export` to each declaration. The file needs no imports.

- [ ] **Step 2: Move the dialog**

Create `src/features/mensagens/components/CreateConversationDialog.tsx` containing, verbatim, `CreateConversationDialog`, the module-level `CREATE_TYPES` constant, the `DirectoryPerson` type, and the helpers used only by the dialog: `mapWorksitePeople`, `mapGlobalPeople`. Export `CreateConversationDialog`; keep the rest module-private. Add the imports it needs:

```tsx
import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";

import type { ConversaTipo, ObraLocalRecord } from "../../../lib/db/db.types";
import {
  buscarColaboradores,
  buscarColaboradoresDaObra,
  type ColaboradorDaObra,
  type ColaboradorLookup,
} from "../../rdos/rdoLookupApi";
import { createConversationApi } from "../mensagensApi";
import { messageFrom } from "../mensagensFormat";
```

- [ ] **Step 3: Move `messageFrom` into `mensagensFormat.ts`**

Both the page and the dialog need it. Append to `src/features/mensagens/mensagensFormat.ts`:

```ts
export function messageFrom(cause: unknown): string {
  return cause instanceof Error
    ? cause.message
    : "Não foi possível concluir a operação.";
}
```

Delete the local copy from `MensagensPage.tsx`.

- [ ] **Step 4: Rewire `MensagensPage.tsx`**

Delete the moved declarations. Add:

```tsx
import { CreateConversationDialog } from "./components/CreateConversationDialog";
import {
  IconCheckDouble,
  IconChevronLeft,
  IconClock,
  IconClose,
  IconFile,
  IconInfo,
  IconPaperclip,
  IconSend,
  IconSpinner,
  IconWarning,
} from "./components/icons";
import { messageFrom } from "./mensagensFormat";
```

Remove now-unused imports (`ChangeEvent`, `createConversationApi`, `buscarColaboradores`, `buscarColaboradoresDaObra`, and the two lookup types) if `eslint` reports them.

- [ ] **Step 5: Verify nothing changed behaviorally**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npm test && npx tsc -b && npm run lint
```

Expected: suite green, `tsc` silent, no lint errors.

- [ ] **Step 6: Confirm the page still renders identically**

```bash
cd /private/tmp/claude-501/-Users-joaolucas-digitalizacao-rdo-stavias/702f330c-fcc8-4fa9-9715-486648955e66/scratchpad
node shot.mjs pos-extracao-1 1440
```

Expected: visually identical to `shots/baseline-1440.png`. Any difference means the move was not verbatim.

- [ ] **Step 7: Commit**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git add apps/web/src/features/mensagens/components/icons.tsx \
        apps/web/src/features/mensagens/components/CreateConversationDialog.tsx
git commit -m "refactor(web): extrai icones e dialogo de nova conversa de Mensagens" -- \
  apps/web/src/features/mensagens/components/icons.tsx \
  apps/web/src/features/mensagens/components/CreateConversationDialog.tsx \
  apps/web/src/features/mensagens/MensagensPage.tsx \
  apps/web/src/features/mensagens/mensagensFormat.ts
```

---

### Task 5: Extract the three panes and the composer

Still a pure move. The visual redesign starts in Task 6.

**Files:**
- Create: `src/features/mensagens/components/ConversationsPane.tsx`
- Create: `src/features/mensagens/components/MessageThread.tsx`
- Create: `src/features/mensagens/components/MessageComposer.tsx`
- Create: `src/features/mensagens/components/ConversationInfoPane.tsx`
- Modify: `src/features/mensagens/MensagensPage.tsx`

**Interfaces:**
- Consumes: `icons.tsx` from Task 4; `MessageTimelineEntry` with `startsRun` from Task 3.
- Produces, exactly these props (later tasks add fields, never rename):

```tsx
export interface ConversationsPaneProps {
  loading: boolean;
  conversations: ConversaLocalRecord[];
  previews: Record<string, ConversationPreview>;
  selectedId: string | null;
  currentUserId: string;
  isOnline: boolean;
  search: string;
  searchResults: MensagemComAnexos[] | null;
  onSearchChange: (value: string) => void;
  onSearchSubmit: (event: FormEvent) => void;
  onCloseSearch: () => void;
  onSelect: (id: string) => void;
  onChooseSearchResult: (message: MensagemComAnexos) => void;
}

export interface MessageThreadProps {
  conversation: ConversaLocalRecord | null;
  title: string;
  scope: string;
  participantCount: number;
  timeline: MessageTimelineEntry<MensagemComAnexos>[];
  hasMessages: boolean;
  currentUserId: string;
  isGroup: boolean;
  now: Date;
  composer: ReactNode;
  onBack: () => void;
  onOpenInfo: () => void;
  onOpenAttachment: (attachment: MensagemAnexoLocalRecord) => Promise<void>;
  onRetry: (messageId: string) => Promise<void>;
}

export interface MessageComposerProps {
  value: string;
  files: File[];
  sending: boolean;
  isOnline: boolean;
  onChange: (value: string) => void;
  onFilesChange: (files: File[]) => void;
  onRemoveFile: (index: number) => void;
  onSubmit: (event: FormEvent) => void;
}

export interface ConversationInfoPaneProps {
  conversation: ConversaLocalRecord | null;
  title: string;
  scope: string;
  messages: MensagemComAnexos[];
  worksites: ObraLocalRecord[];
  onBack: () => void;
  onClose: () => void;
  onOpenAttachment: (attachment: MensagemAnexoLocalRecord) => Promise<void>;
}
```

- [ ] **Step 1: Create `ConversationsPane.tsx`**

Move the `<aside className="mensagens-conversations">` subtree from `MensagensPage.tsx` plus the `ConversationList` and `SearchResults` components. `ConversationList` and `SearchResults` become module-private. The pane owns the pane heading, the search form, and the branch between search results and the conversation list. `conversationName` and `conversationScope` move to `mensagensView.ts` and are exported from there, because the page, the thread, and the info pane all need them.

- [ ] **Step 2: Move `conversationName`, `conversationScope`, and `activeParticipant` into `mensagensView.ts`**

Append to `src/features/mensagens/mensagensView.ts`, exporting all three, and add the `ConversaLocalRecord` and `ConversaTipo` imports:

```ts
export function conversationName(
  conversation: ConversaLocalRecord,
  currentUserId?: string,
): string {
  if (conversation.titulo) return conversation.titulo;
  const others = conversation.participantes
    .filter(activeParticipant)
    .filter((participant) => participant.colaboradorId !== currentUserId)
    .map((participant) => participant.nome);
  return others.join(", ") || "Conversa direta";
}

export function conversationScope(conversation: ConversaLocalRecord): string {
  const labels: Record<ConversaTipo, string> = {
    DIRETA: "Conversa direta",
    GRUPO: "Grupo",
    EQUIPE: "Equipe da obra",
    OBRA: "Conversa da obra",
  };
  return labels[conversation.tipo];
}

export function activeParticipant(
  participant: ConversaLocalRecord["participantes"][number],
): boolean {
  return participant.status === "ATIVO";
}
```

Delete the three local copies from `MensagensPage.tsx`.

- [ ] **Step 3: Create `MessageThread.tsx`**

Move the thread header, `<ol className="mensagens-list">`, the empty state, `MessageItem`, and `MessageTick`. `MessageItem` and `MessageTick` stay module-private. Render `{composer}` where the `<form className="mensagens-composer">` used to be.

- [ ] **Step 4: Create `MessageComposer.tsx`**

Move the `<form className="mensagens-composer">` subtree, including the file preview list, the textarea auto-grow `useEffect`, and the `textareaRef`/`fileInput` refs. The refs move with the component; the page keeps neither.

- [ ] **Step 5: Create `ConversationInfoPane.tsx`**

Move the existing `ConversationContext` component, renaming it `ConversationInfoPane`. Move `shortIdentifier` in with it as module-private.

- [ ] **Step 6: Rewire `MensagensPage.tsx`**

The page keeps: all `useState`/`useEffect`/`useCallback` hooks, `handleRefresh`, `handleSend`, `handleSearch`, `handleRetry`, `openAttachment`, `chooseSearchResult`, `chooseConversation`, `openContext`, and the JSX skeleton that composes the panes.

- [ ] **Step 7: Verify no behavior changed**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npm test && npx tsc -b && npm run lint
```

Expected: green, silent, clean.

- [ ] **Step 8: Confirm pixel parity with the baseline**

```bash
cd /private/tmp/claude-501/-Users-joaolucas-digitalizacao-rdo-stavias/702f330c-fcc8-4fa9-9715-486648955e66/scratchpad
node shot.mjs pos-extracao-2 1440
```

Expected: visually identical to `shots/baseline-1440.png`.

- [ ] **Step 9: Commit**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git add apps/web/src/features/mensagens/components/
git commit -m "refactor(web): divide a pagina de Mensagens em paineis" -- \
  apps/web/src/features/mensagens/components/ \
  apps/web/src/features/mensagens/MensagensPage.tsx \
  apps/web/src/features/mensagens/mensagensView.ts
```

---

### Task 6: Three-column layout with a container query

**Files:**
- Modify: `src/features/mensagens/MensagensPage.css`
- Modify: `src/features/mensagens/MensagensPage.tsx`
- Create: `src/features/mensagens/mensagensLayout.test.ts`

**Interfaces:**
- Consumes: the pane components from Task 5.
- Produces: `MensagensPage.tsx` renders `<div className="mensagens-frame">` wrapping `<section className="mensagens-workspace">`, and the workspace carries `mensagens-workspace--info-hidden` when the info column is collapsed. Persistence key: `cortex.ui.mensagensContextoRecolhido`, `"1"` meaning collapsed.

- [ ] **Step 1: Write the failing CSS contract test**

Create `src/features/mensagens/mensagensLayout.test.ts`:

```ts
import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const css = readFileSync(
  new URL("./MensagensPage.css", import.meta.url),
  "utf8",
);

function rule(selector: string): string {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) {
    throw new Error(`Regra CSS ausente: ${selector}`);
  }
  const end = css.indexOf("\n}", start);
  if (end < 0) {
    throw new Error(`Regra CSS sem fechamento: ${selector}`);
  }
  return css.slice(start, end + 2);
}

describe("layout da aba Mensagens", () => {
  it("consulta a largura do frame, não a da viewport", () => {
    expect(rule(".mensagens-frame")).toContain("container-type: inline-size;");
    expect(css).toContain("@container (min-width: 640px)");
    expect(css).toContain("@container (min-width: 1040px)");
  });

  it("empilha em painel único antes de 640px de frame", () => {
    expect(rule(".mensagens-workspace")).toContain(
      "grid-template-columns: minmax(0, 1fr);",
    );
  });

  it("mantém a terceira coluna fora do fluxo quando recolhida", () => {
    expect(rule(".mensagens-workspace--info-hidden")).toContain(
      "grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);",
    );
  });

  it("não introduz tema escuro nesta aba", () => {
    expect(css).not.toContain("prefers-color-scheme: dark");
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npx vitest run src/features/mensagens/mensagensLayout.test.ts
```

Expected: FAIL — `Regra CSS ausente: .mensagens-frame`.

- [ ] **Step 3: Replace the workspace layout rules in `MensagensPage.css`**

Replace the existing `.mensagens-workspace` rule and the two `@media` blocks at the bottom of the file with:

```css
.mensagens-frame {
  container-type: inline-size;
}

.mensagens-workspace {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-height: min(760px, calc(100vh - 165px));
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-surface);
}

/* Painel único: só o painel escolhido por mobilePane aparece. */
.mensagens-conversations,
.mensagens-thread,
.mensagens-context {
  display: none;
}

.mensagens-workspace--list .mensagens-conversations { display: flex; }
.mensagens-workspace--thread .mensagens-thread { display: grid; }
.mensagens-workspace--context .mensagens-context { display: flex; }

@container (min-width: 640px) {
  .mensagens-workspace {
    grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  }

  .mensagens-conversations { display: flex; }
  .mensagens-thread { display: grid; }

  /* Contexto volta a ser gaveta sobreposta nesta faixa. */
  .mensagens-context {
    position: absolute;
    z-index: 6;
    display: flex;
    inset: 0 0 0 auto;
    width: min(340px, 86%);
    border-left: 1px solid var(--color-border);
    box-shadow: -16px 0 44px rgb(18 35 31 / 16%);
    transform: translateX(102%);
    transition: transform 0.22s ease;
    pointer-events: none;
  }

  .mensagens-workspace--drawer-open .mensagens-context {
    transform: translateX(0);
    pointer-events: auto;
  }
}

@container (min-width: 1040px) {
  .mensagens-workspace {
    grid-template-columns: minmax(260px, 340px) minmax(0, 1fr) 320px;
  }

  /* Terceira coluna em fluxo: sem gaveta, sem sombra, sem transform. */
  .mensagens-context {
    position: static;
    width: auto;
    box-shadow: none;
    transform: none;
    pointer-events: auto;
  }

  .mensagens-drawer-backdrop,
  .mensagens-drawer-close {
    display: none;
  }

  .mensagens-workspace--info-hidden {
    grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  }

  .mensagens-workspace--info-hidden .mensagens-context {
    display: none;
  }
}

.mensagens-context {
  min-width: 0;
  flex-direction: column;
  overflow-y: auto;
  background: #fcfdfc;
}

.mensagens-conversations {
  min-width: 0;
  flex-direction: column;
  border-right: 1px solid var(--color-border);
  background: #f8faf9;
}
```

Delete the old `.mensagens-context` positioning block near "Context drawer", the old `.mensagens-conversations` rule, and the `@media (max-width: 900px)` display-toggle rules that this replaces. Keep the `@media (max-width: 700px)` block for page padding and the `@media (prefers-reduced-motion: reduce)` block.

- [ ] **Step 4: Add the frame wrapper and the collapse toggle in `MensagensPage.tsx`**

Add the storage key and state near the other `useState` calls:

```tsx
const INFO_COLLAPSED_KEY = "cortex.ui.mensagensContextoRecolhido";
```

```tsx
const [infoCollapsed, setInfoCollapsed] = useState(
  () => localStorage.getItem(INFO_COLLAPSED_KEY) === "1",
);

function toggleInfoCollapsed() {
  setInfoCollapsed((current) => {
    const next = !current;
    localStorage.setItem(INFO_COLLAPSED_KEY, next ? "1" : "0");
    return next;
  });
}
```

Wrap the workspace and add the modifier class:

```tsx
<div className="mensagens-frame">
  <section
    className={`mensagens-workspace mensagens-workspace--${mobilePane}${
      contextOpen ? " mensagens-workspace--drawer-open" : ""
    }${infoCollapsed ? " mensagens-workspace--info-hidden" : ""}`}
    aria-label="Mensagens"
  >
    {/* panes unchanged */}
  </section>
</div>
```

Pass `onOpenInfo={() => { toggleInfoCollapsed(); openContext(); }}` — the same header button collapses the in-flow column on wide frames and opens the drawer on narrow ones. Because the drawer is invisible at `≥1040px` and the collapsed column is invisible below it, one handler serves both.

- [ ] **Step 5: Run the contract test and the suite**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npx vitest run src/features/mensagens/mensagensLayout.test.ts && npm test && npx tsc -b
```

Expected: 4 new tests pass, suite green, `tsc` silent.

- [ ] **Step 6: Verify all three width bands**

```bash
cd /private/tmp/claude-501/-Users-joaolucas-digitalizacao-rdo-stavias/702f330c-fcc8-4fa9-9715-486648955e66/scratchpad
node shot.mjs layout 1440 && node shot.mjs layout 1100 && node shot.mjs layout 390 && node shot.mjs layout-recolhido 1440 --info-collapsed
```

Expected: 1440 shows three columns; 1100 shows two with the info pane hidden until opened; 390 shows a single pane; `layout-recolhido` shows two columns with the third collapsed.

- [ ] **Step 7: Commit**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git add apps/web/src/features/mensagens/mensagensLayout.test.ts
git commit -m "feat(web): tres colunas em Mensagens com container query" -- \
  apps/web/src/features/mensagens/MensagensPage.css \
  apps/web/src/features/mensagens/MensagensPage.tsx \
  apps/web/src/features/mensagens/mensagensLayout.test.ts
```

---

### Task 7: Message runs — avatar, caption, relative time, bubble palette

**Files:**
- Modify: `src/features/mensagens/components/MessageThread.tsx`
- Modify: `src/features/mensagens/MensagensPage.tsx`
- Modify: `src/features/mensagens/MensagensPage.css`

**Interfaces:**
- Consumes: `formatRelativeTime`, `formatClock`, `initials` (Task 2); `startsRun` (Task 3); `MessageThreadProps.now` (Task 5).
- Produces: no new exported symbol.

- [ ] **Step 1: Add the minute tick to `MensagensPage.tsx`**

Captions render relative labels, so they go stale while the tab sits open.

```tsx
const [now, setNow] = useState(() => new Date());

useEffect(() => {
  const id = window.setInterval(() => setNow(new Date()), 60_000);
  return () => window.clearInterval(id);
}, []);
```

Pass `now={now}` to `MessageThread`.

- [ ] **Step 2: Replace `MessageItem` in `MessageThread.tsx`**

```tsx
function MessageItem({
  message,
  startsRun,
  mine,
  showAuthorName,
  now,
  onOpenAttachment,
  onRetry,
}: {
  message: MensagemComAnexos;
  startsRun: boolean;
  mine: boolean;
  showAuthorName: boolean;
  now: Date;
  onOpenAttachment: (attachment: MensagemAnexoLocalRecord) => Promise<void>;
  onRetry: (messageId: string) => Promise<void>;
}) {
  const failed = message.syncStatus === "FALHOU";
  return (
    <li
      className={`mensagem-item${mine ? " mensagem-item--mine" : ""}${
        startsRun ? " mensagem-item--lead" : ""
      }`}
    >
      {startsRun ? (
        <p className="mensagem-caption">
          {!mine && showAuthorName ? (
            <span className="mensagem-caption-nome">{message.autorNome}</span>
          ) : null}
          <time dateTime={message.criadaNoClienteEm} title={formatClock(message.criadaNoClienteEm)}>
            {formatRelativeTime(message.criadaNoClienteEm, now)}
          </time>
        </p>
      ) : null}
      <div className="mensagem-linha">
        {mine ? null : (
          <span
            className={`mensagem-avatar-mini${
              startsRun ? "" : " mensagem-avatar-mini--vazio"
            }`}
            aria-hidden="true"
          >
            {startsRun ? initials(message.autorNome) : ""}
          </span>
        )}
        <div
          className={`mensagem-bubble ${
            mine ? "mensagem-bubble--mine" : "mensagem-bubble--in"
          }`}
        >
          {message.status === "EXCLUIDA" ? (
            <p className="mensagem-deleted">Mensagem excluída</p>
          ) : message.corpo ? (
            <p className="mensagem-corpo">{message.corpo}</p>
          ) : null}
          {message.anexos.length > 0 ? (
            <ul className="mensagem-attachments">
              {message.anexos.map((attachment) => (
                <li key={attachment.id}>
                  <button type="button" onClick={() => void onOpenAttachment(attachment)}>
                    <IconFile />
                    <span>{attachment.nome}</span>
                    <small>{formatFileSize(attachment.tamanhoBytes)}</small>
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
          {mine ? (
            <span className="mensagem-meta">
              <MessageTick status={message.syncStatus} />
            </span>
          ) : null}
          {failed ? (
            <div className="mensagem-retry">
              <span>Falha ao enviar</span>
              <button type="button" onClick={() => void onRetry(message.id)}>
                Tentar novamente
              </button>
            </div>
          ) : null}
          {message.ultimoErro ? (
            <details className="mensagem-error">
              <summary>Detalhes da sincronização</summary>
              <p>{message.ultimoErro}</p>
            </details>
          ) : null}
        </div>
      </div>
    </li>
  );
}
```

Update the `timeline.map` call site to pass `startsRun={entry.startsRun}` and `showAuthorName={isGroup}` and `now={now}`.

- [ ] **Step 3: Replace the bubble styles in `MensagensPage.css`**

Replace everything from `.mensagem-item` through `.mensagem-tick--fail`, and delete the four tail rules (`.mensagem-bubble--in.mensagem-bubble--tail`, its `::before`, and the two `--mine` equivalents) — the reference has no tails:

```css
.mensagem-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.mensagem-item--mine {
  align-items: flex-end;
}

.mensagem-item--lead {
  margin-top: 16px;
}

.mensagens-list > .mensagem-item:first-of-type {
  margin-top: 0;
}

.mensagem-caption {
  display: flex;
  align-items: baseline;
  gap: 7px;
  margin: 0 0 5px;
  color: #7d8a83;
  font-size: 0.68rem;
}

.mensagem-item:not(.mensagem-item--mine) .mensagem-caption {
  padding-left: 42px;
}

.mensagem-caption-nome {
  color: #2b4a41;
  font-weight: 650;
}

.mensagem-linha {
  display: flex;
  max-width: min(560px, 82%);
  align-items: flex-end;
  gap: 10px;
}

.mensagem-avatar-mini {
  display: grid;
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 50%;
  background: #d7e6e0;
  color: #124e4a;
  font-size: 0.7rem;
  font-weight: 700;
}

.mensagem-avatar-mini--vazio {
  background: transparent;
}

.mensagem-bubble {
  position: relative;
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: flex-end;
  column-gap: 8px;
  row-gap: 2px;
  padding: 9px 13px;
  border-radius: 14px;
}

.mensagem-bubble--in {
  border: 1px solid #e6ebe8;
  background: #f4f6f5;
  color: var(--color-text);
}

.mensagem-bubble--mine {
  border: 1px solid #cbe2da;
  background: #d9ece6;
  color: #123f39;
}

.mensagem-corpo {
  margin: 0;
  flex: 0 1 auto;
  max-width: 100%;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  font-size: 0.9rem;
  line-height: 1.45;
}

.mensagem-deleted {
  margin: 0;
  flex: 0 1 auto;
  color: #738078;
  font-size: 0.88rem;
  font-style: italic;
}

.mensagem-meta {
  display: inline-flex;
  align-items: center;
  margin-left: auto;
  color: #5c7d73;
}

.mensagem-tick {
  width: 15px;
  height: 15px;
}

.mensagem-tick--fail {
  color: #a3312a;
}
```

Also update the three rules that assumed a dark own-bubble — they now sit on a light surface:

```css
.mensagem-bubble--mine .mensagem-attachments button {
  border-color: #bcd8ce;
  background: rgb(255 255 255 / 70%);
  color: #24483e;
}

.mensagem-bubble--mine .mensagem-attachments small {
  color: #5d7a70;
}

.mensagem-bubble--mine .mensagem-deleted {
  color: #5d7a70;
}

.mensagem-retry {
  display: flex;
  flex: 0 0 100%;
  align-items: center;
  gap: 9px;
  margin-top: 4px;
  color: #a3312a;
  font-size: 0.68rem;
}

.mensagem-retry button {
  border: 0;
  background: transparent;
  color: #a3312a;
  font-size: inherit;
  font-weight: 650;
  text-decoration: underline;
  cursor: pointer;
}

.mensagem-error summary {
  color: #5d7a70;
  cursor: pointer;
}

.mensagem-error p {
  margin: 5px 0 0;
  color: #4a5851;
}
```

- [ ] **Step 4: Add a contract assertion for the failure palette**

The failure affordances used to be white-on-dark. Add to `describe("layout da aba Mensagens", ...)` in `mensagensLayout.test.ts`:

```ts
  it("mantém o estado de falha legível sobre bolha clara", () => {
    expect(rule(".mensagem-retry")).toContain("color: #a3312a;");
    expect(rule(".mensagem-retry button")).toContain("color: #a3312a;");
    expect(css).not.toContain("color: #ffd0c9;");
  });
```

- [ ] **Step 5: Run the tests**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npm test && npx tsc -b && npm run lint
```

Expected: green, silent, clean.

- [ ] **Step 6: Verify the runs render**

```bash
cd /private/tmp/claude-501/-Users-joaolucas-digitalizacao-rdo-stavias/702f330c-fcc8-4fa9-9715-486648955e66/scratchpad
node shot.mjs runs 1440
```

Expected: captions above each run (`João Souza · há 6 h`, `há 12 min`), 32px avatars only on the first bubble of an incoming run, continuation bubbles indented to align, own bubbles in teal tint with a tick, no tails.

- [ ] **Step 7: Commit**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git commit -m "feat(web): agrupa mensagens em runs com legenda de tempo relativo" -- \
  apps/web/src/features/mensagens/components/MessageThread.tsx \
  apps/web/src/features/mensagens/MensagensPage.tsx \
  apps/web/src/features/mensagens/MensagensPage.css \
  apps/web/src/features/mensagens/mensagensLayout.test.ts
```

---

### Task 8: Conversations pane — search field, "Você:" prefix, pending dot

**Files:**
- Modify: `src/features/mensagens/components/ConversationsPane.tsx`
- Modify: `src/features/mensagens/MensagensPage.css`

**Interfaces:**
- Consumes: `previewLabel`, `hasPendingMessage` (Task 3); `formatRelativeTime`, `initials` (Task 2).
- Produces: no new exported symbol. `ConversationsPaneProps` gains `now: Date`.

- [ ] **Step 1: Replace the search form**

```tsx
<form className="mensagens-search" onSubmit={props.onSearchSubmit} role="search">
  <label className="mensagens-visually-hidden" htmlFor="mensagens-search">
    Buscar no histórico
  </label>
  <div className="mensagens-search-field">
    <IconSearch />
    <input
      id="mensagens-search"
      value={props.search}
      onChange={(event) => props.onSearchChange(event.target.value)}
      placeholder="Buscar conversas…"
    />
    {props.search ? (
      <button type="button" onClick={props.onCloseSearch} aria-label="Limpar busca">
        <IconClose />
      </button>
    ) : null}
  </div>
</form>
```

- [ ] **Step 2: Add `IconSearch` to `components/icons.tsx`**

```tsx
export function IconSearch() {
  return (
    <svg
      width="17"
      height="17"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <circle cx="11" cy="11" r="7" />
      <line x1="16.2" y1="16.2" x2="21" y2="21" />
    </svg>
  );
}
```

- [ ] **Step 3: Replace the conversation row markup**

```tsx
<li key={conversation.id}>
  <button
    type="button"
    className={conversation.id === props.selectedId ? "active" : ""}
    onClick={() => props.onSelect(conversation.id)}
  >
    <span className="mensagens-avatar" aria-hidden="true">
      {initials(conversationName(conversation, props.currentUserId))}
      {hasPendingMessage(props.previews[conversation.id]) ? (
        <i className="mensagens-avatar-dot" />
      ) : null}
    </span>
    <span className="mensagens-row-body">
      <strong>{conversationName(conversation, props.currentUserId)}</strong>
      <small>
        {previewLabel(
          props.previews[conversation.id],
          props.currentUserId,
          conversationScope(conversation),
        )}
      </small>
    </span>
    <time>
      {formatRelativeTime(
        props.previews[conversation.id]?.at ?? conversation.atualizadaEm,
        props.now,
      )}
    </time>
  </button>
</li>
```

- [ ] **Step 4: Style the search field, the dot, and the active row**

```css
.mensagens-visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.mensagens-search {
  padding: 12px;
  border-bottom: 1px solid var(--color-border);
}

.mensagens-search-field {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  border: 1px solid #cfd8d2;
  border-radius: 999px;
  background: #fff;
  color: #5f6d66;
}

.mensagens-search-field:focus-within {
  border-color: var(--color-focus);
}

.mensagens-search-field input {
  min-width: 0;
  flex: 1 1 auto;
  padding: 9px 0;
  border: 0;
  background: transparent;
  color: var(--color-text);
  font-size: 0.84rem;
}

.mensagens-search-field input:focus {
  outline: none;
}

.mensagens-search-field button {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  padding: 0;
  border: 0;
  background: transparent;
  color: #7d8a83;
  cursor: pointer;
}

.mensagens-avatar {
  position: relative;
}

.mensagens-avatar-dot {
  position: absolute;
  right: -1px;
  bottom: -1px;
  width: 11px;
  height: 11px;
  border: 2px solid #f8faf9;
  border-radius: 50%;
  background: #e2a11c;
}

.mensagens-row-body {
  min-width: 0;
}

.mensagens-conversation-list > li > button.active {
  background: #e6f0ec;
  box-shadow: inset 3px 0 var(--color-brand-teal);
}
```

Delete the old `.mensagens-search > label`, `.mensagens-search > div`, `.mensagens-search input`, and `.mensagens-search button` rules that these replace. Keep `.mensagens-search-results header button` styling by giving it its own rule with the border/radius/padding values it previously shared.

- [ ] **Step 5: Pass `now` from the page**

Add `now={now}` to the `<ConversationsPane />` call in `MensagensPage.tsx` and `now: Date` to `ConversationsPaneProps`.

- [ ] **Step 6: Run the tests**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npm test && npx tsc -b && npm run lint
```

Expected: green, silent, clean.

- [ ] **Step 7: Verify the list**

```bash
cd /private/tmp/claude-501/-Users-joaolucas-digitalizacao-rdo-stavias/702f330c-fcc8-4fa9-9715-486648955e66/scratchpad
node shot.mjs lista 1440
```

Expected: rounded search field with the magnifier inside; rows showing `Você: Registrado.` style previews and relative times; the active row filled with the teal tint and its left bar.

- [ ] **Step 8: Commit**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git commit -m "feat(web): renova a lista de conversas com busca embutida e previa propria" -- \
  apps/web/src/features/mensagens/components/ConversationsPane.tsx \
  apps/web/src/features/mensagens/components/icons.tsx \
  apps/web/src/features/mensagens/MensagensPage.tsx \
  apps/web/src/features/mensagens/MensagensPage.css
```

---

### Task 9: Info pane — identity block, collapsible sections, sync line

**Files:**
- Modify: `src/features/mensagens/components/ConversationInfoPane.tsx`
- Modify: `src/features/mensagens/MensagensPage.tsx`
- Modify: `src/features/mensagens/MensagensPage.css`

**Interfaces:**
- Consumes: `useSyncStatus` from `src/lib/sync/useSyncStatus.ts`, whose `snapshot` carries `lastSyncCompletedAt: string | null` and `isOnline: boolean`; `formatRelativeTime`, `initials`, `formatFileSize` (Task 2).
- Produces: `ConversationInfoPaneProps` gains `now: Date`, `isOnline: boolean`, `lastSyncCompletedAt: string | null`.

- [ ] **Step 1: Add the collapsible section component inside `ConversationInfoPane.tsx`**

```tsx
function InfoSection({
  title,
  defaultOpen,
  children,
}: {
  title: string;
  defaultOpen: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <section className="mensagens-info-section">
      <button
        type="button"
        className="mensagens-info-toggle"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <span>{title}</span>
        <IconChevronDown className={open ? "" : "mensagens-chevron--fechado"} />
      </button>
      {open ? <div className="mensagens-info-body">{children}</div> : null}
    </section>
  );
}
```

- [ ] **Step 2: Add `IconChevronDown` to `components/icons.tsx`**

```tsx
export function IconChevronDown({ className }: IconProps) {
  return (
    <svg
      className={className}
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <polyline points="6 9 12 15 18 9" />
    </svg>
  );
}
```

- [ ] **Step 3: Rebuild the pane body**

Replace the pane's `<section>` children with the identity block plus three `InfoSection`s:

```tsx
<div className="mensagens-info-identity">
  <span className="mensagens-info-avatar" aria-hidden="true">
    {initials(props.title)}
  </span>
  <strong>{props.title}</strong>
  <small>{props.scope}</small>
</div>

<InfoSection title="Informação" defaultOpen>
  {conversation.obraId ? (
    <p className="mensagens-info-linha">
      <strong>{worksite?.nome ?? "Obra vinculada"}</strong>
      <small>{worksite?.codigoContrato ?? shortIdentifier(conversation.obraId)}</small>
    </p>
  ) : null}
  <p className="mensagens-info-linha">
    <strong>{participants.length} participantes</strong>
  </p>
  <p className="mensagens-info-linha">
    <strong>{syncLabel(props.isOnline, props.lastSyncCompletedAt, props.now)}</strong>
  </p>
</InfoSection>

<InfoSection title={`Pessoas (${participants.length})`} defaultOpen>
  <ul className="mensagens-context-people">{/* rows unchanged */}</ul>
</InfoSection>

<InfoSection title={`Anexos (${attachments.length})`} defaultOpen={false}>
  {attachments.length === 0 ? (
    <p className="mensagens-info-vazio">Nenhum documento nesta conversa.</p>
  ) : (
    <ul className="mensagens-context-documents">{/* rows unchanged */}</ul>
  )}
</InfoSection>
```

And the label helper, module-private in the same file:

```tsx
function syncLabel(
  isOnline: boolean,
  lastSyncCompletedAt: string | null,
  now: Date,
): string {
  if (!isOnline) {
    return "Offline";
  }
  if (!lastSyncCompletedAt) {
    return "Nunca sincronizado";
  }
  return `Sincronizado ${formatRelativeTime(lastSyncCompletedAt, now)}`;
}
```

- [ ] **Step 4: Wire the sync snapshot in `MensagensPage.tsx`**

```tsx
import { useSyncStatus } from "../../lib/sync/useSyncStatus";
```

```tsx
const { snapshot } = useSyncStatus();
```

Pass `isOnline={snapshot.isOnline}`, `lastSyncCompletedAt={snapshot.lastSyncCompletedAt}`, and `now={now}` to `ConversationInfoPane`. The hook refreshes itself on an interval and on online/offline events, so nothing else has to drive it.

- [ ] **Step 5: Style the pane**

```css
.mensagens-info-identity {
  display: grid;
  justify-items: center;
  gap: 6px;
  padding: 28px 20px 22px;
  border-bottom: 1px solid #e5eae7;
  text-align: center;
}

.mensagens-info-avatar {
  display: grid;
  width: 84px;
  height: 84px;
  place-items: center;
  border-radius: 50%;
  background: #d7e6e0;
  color: #124e4a;
  font-size: 1.7rem;
  font-weight: 700;
}

.mensagens-info-identity strong {
  font-size: 0.92rem;
  line-height: 1.35;
}

.mensagens-info-identity small {
  color: var(--color-muted);
  font-size: 0.7rem;
}

.mensagens-info-section {
  border-bottom: 1px solid #e5eae7;
}

.mensagens-info-toggle {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border: 0;
  background: transparent;
  color: #758079;
  font-size: 0.65rem;
  font-weight: 650;
  letter-spacing: 0.07em;
  text-transform: uppercase;
  cursor: pointer;
}

.mensagens-info-toggle:hover {
  color: var(--color-brand-teal);
}

.mensagens-chevron--fechado {
  transform: rotate(-90deg);
}

.mensagens-info-body {
  padding: 0 16px 16px;
}

.mensagens-info-linha {
  display: grid;
  gap: 2px;
  margin: 0 0 10px;
}

.mensagens-info-linha strong { font-size: 0.78rem; }
.mensagens-info-linha small { color: var(--color-muted); font-size: 0.68rem; }

.mensagens-info-vazio {
  margin: 0;
  color: var(--color-muted);
  font-size: 0.74rem;
}
```

Delete the old `.mensagens-context > section` and `.mensagens-context h3` rules.

- [ ] **Step 6: Run the tests**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npm test && npx tsc -b && npm run lint
```

Expected: green, silent, clean.

- [ ] **Step 7: Verify the pane, open and collapsed**

```bash
cd /private/tmp/claude-501/-Users-joaolucas-digitalizacao-rdo-stavias/702f330c-fcc8-4fa9-9715-486648955e66/scratchpad
node shot.mjs contexto 1440
```

Expected: large initials avatar, name, scope; `INFORMAÇÃO` and `PESSOAS` expanded; `ANEXOS (1)` collapsed with its chevron rotated. Click `ANEXOS` in a real browser and confirm it expands and the file row downloads.

- [ ] **Step 8: Commit**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git commit -m "feat(web): reorganiza o contexto da conversa em secoes recolhiveis" -- \
  apps/web/src/features/mensagens/components/ConversationInfoPane.tsx \
  apps/web/src/features/mensagens/components/icons.tsx \
  apps/web/src/features/mensagens/MensagensPage.tsx \
  apps/web/src/features/mensagens/MensagensPage.css
```

---

### Task 10: Composer bar

**Files:**
- Modify: `src/features/mensagens/components/MessageComposer.tsx`
- Modify: `src/features/mensagens/components/icons.tsx`
- Modify: `src/features/mensagens/MensagensPage.css`

**Interfaces:**
- Consumes: `MessageComposerProps` (Task 5).
- Produces: no new exported symbol.

- [ ] **Step 1: Add `IconImage` to `components/icons.tsx`**

```tsx
export function IconImage() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <rect x="3" y="4.5" width="18" height="15" rx="2.5" />
      <circle cx="8.5" cy="10" r="1.6" />
      <path d="M4 17.5 9.5 12l4 4 2.5-2 4 3.5" />
    </svg>
  );
}
```

- [ ] **Step 2: Rebuild the composer bar**

Both file inputs write to the same `files` state through `onFilesChange`:

```tsx
<div className="mensagens-composer-bar">
  <textarea
    ref={textareaRef}
    id="mensagem-body"
    value={props.value}
    onChange={(event) => props.onChange(event.target.value)}
    onKeyDown={(event) => {
      if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
        event.preventDefault();
        event.currentTarget.form?.requestSubmit();
      }
    }}
    placeholder="Mensagem"
    rows={1}
  />
  <label className="mensagens-attach" aria-label="Anexar foto">
    <input
      type="file"
      accept="image/*"
      multiple
      onChange={(event) => props.onFilesChange(Array.from(event.target.files ?? []))}
    />
    <IconImage />
  </label>
  <label className="mensagens-attach" aria-label="Anexar arquivos">
    <input
      ref={fileInput}
      type="file"
      multiple
      onChange={(event) => props.onFilesChange(Array.from(event.target.files ?? []))}
    />
    <IconPaperclip />
  </label>
  <button
    type="submit"
    className="mensagens-send"
    disabled={props.sending || (!props.value.trim() && props.files.length === 0)}
    aria-label="Enviar mensagem"
  >
    {props.sending ? <IconSpinner /> : <IconSend />}
  </button>
</div>
```

- [ ] **Step 3: Style the single bar**

```css
.mensagens-composer {
  padding: 12px 14px 16px;
  border-top: 1px solid var(--color-border);
  background: #fff;
}

.mensagens-composer-bar {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  padding: 4px 4px 4px 6px;
  border: 1px solid #d7ddd8;
  border-radius: 24px;
  background: #fff;
}

.mensagens-composer-bar:focus-within {
  border-color: #b9c4be;
}

.mensagens-composer textarea {
  display: block;
  width: 100%;
  min-height: 38px;
  max-height: 140px;
  flex: 1 1 auto;
  padding: 9px 10px;
  border: 0;
  background: transparent;
  color: var(--color-text);
  line-height: 1.4;
  resize: none;
}

.mensagens-composer textarea:focus {
  outline: none;
}

.mensagens-attach {
  display: grid;
  flex: 0 0 auto;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 50%;
  color: #4d5b54;
  cursor: pointer;
}

.mensagens-attach:hover {
  background: #eef2ef;
  color: var(--color-brand-teal);
}

.mensagens-attach input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

.mensagens-send {
  display: grid;
  flex: 0 0 auto;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: #fed203;
  color: #143c36;
  cursor: pointer;
}

.mensagens-send:hover:not(:disabled) {
  background: #f2c800;
}

.mensagens-send:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.mensagens-composer-hint {
  display: block;
  margin: 8px 0 0 14px;
  color: #a15a2a;
  font-size: 0.68rem;
}

.mensagens-file-preview {
  display: flex;
  gap: 7px;
  overflow-x: auto;
  margin-bottom: 9px;
  padding-left: 8px;
}
```

- [ ] **Step 4: Run the tests**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npm test && npx tsc -b && npm run lint
```

Expected: green, silent, clean.

- [ ] **Step 5: Verify the composer end to end**

```bash
cd /private/tmp/claude-501/-Users-joaolucas-digitalizacao-rdo-stavias/702f330c-fcc8-4fa9-9715-486648955e66/scratchpad
node shot.mjs composer 1440
```

Then in a real browser at `http://127.0.0.1:5174/mensagens`: type a message and confirm the bar grows to two lines without the icons drifting; attach a file and confirm the chip appears above the bar; confirm the send button is disabled with an empty field and enabled once text is typed.

- [ ] **Step 6: Commit**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git commit -m "feat(web): unifica o composer de Mensagens em barra unica" -- \
  apps/web/src/features/mensagens/components/MessageComposer.tsx \
  apps/web/src/features/mensagens/components/icons.tsx \
  apps/web/src/features/mensagens/MensagensPage.css
```

---

### Task 11: Full verification pass

**Files:**
- Modify (only if a defect is found): any file from Tasks 6–10.

**Interfaces:**
- Consumes: everything.
- Produces: nothing.

- [ ] **Step 1: Run the whole suite, typecheck, lint, and production build**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/apps/web
npm test && npx tsc -b && npm run lint && npm run build
```

Expected: suite green, `tsc` silent, no lint errors, build succeeds.

- [ ] **Step 2: Capture the four verification widths**

```bash
cd /private/tmp/claude-501/-Users-joaolucas-digitalizacao-rdo-stavias/702f330c-fcc8-4fa9-9715-486648955e66/scratchpad
node shot.mjs final 1440 && node shot.mjs final 1100 && node shot.mjs final 900 && node shot.mjs final 390
```

Expected: 1440 three columns, 1100 two columns, 900 two columns, 390 single pane. No horizontal scrollbar at any width.

- [ ] **Step 3: Verify the resizable-sidebar case that motivated the container query**

In a real browser at 1440px, drag the shell sidebar to its maximum width and watch the Mensagens info column. It must drop out of flow into drawer behavior as the frame crosses 1040px — not stay wedged in three columns. Drag back and confirm it returns.

- [ ] **Step 4: Verify keyboard and screen-reader affordances**

Tab through the page and confirm: the search field, its clear button, every conversation row, the info toggle, the three section toggles, both attach buttons, and send all take focus with a visible ring. Confirm the section toggles report `aria-expanded` correctly in the accessibility inspector.

- [ ] **Step 5: Verify offline behavior is untouched**

In DevTools, set the network to offline. Confirm the info pane's sync line reads `Offline`, the composer hint appears, a sent message queues with the pending tick, and the conversation row shows the amber dot.

- [ ] **Step 6: Update the project memory**

Rewrite `/Users/joaolucas/.claude/projects/-Users-joaolucas-digitalizacao-rdo-stavias/memory/mensagens-whatsapp-redesign.md` to describe the three-column layout that shipped, and correct `stavia-web-visual-verification.md`, which still claims Playwright can authenticate by injecting `cortex.auth.sessao` — that key is now only purged as legacy storage, and the working technique is stubbing `**/api/**` including `/auth/session`.

---

## Self-Review

**Spec coverage:** Visual direction → Tasks 7–10. Third column with container query → Task 6. Presence replaced by sync/scope line → Task 9. Relative-time captions → Tasks 2 and 7. Ticks kept per message → Task 7 Step 2. Amber pending dot → Task 8. Search field → Task 8. Composer → Task 10. Collapsible info sections → Task 9. Component split → Tasks 4 and 5. Tests table → Tasks 2, 3, 6, 7. Playwright verification → Tasks 1, 6–11. Risks → Task 6 (collapse chevron), Task 11 Step 3 (sidebar drag), Task 7 (failure palette), Task 7 Step 1 (60s tick).

**Type consistency:** `startsRun` is introduced in Task 3 and consumed under that name in Task 7. `ConversationPreview.authorId` is added in Task 3 and read by `previewLabel` in Task 8. `now: Date` flows from Task 7 Step 1 into `MessageThreadProps` (Task 5), `ConversationsPaneProps` (Task 8 Step 5), and `ConversationInfoPaneProps` (Task 9). `formatRelativeTime`, `formatClock`, `formatFileSize`, `initials`, and `messageFrom` are all defined in Task 2 or Task 4 Step 3 before any consumer. `IconSearch`, `IconChevronDown`, and `IconImage` are each added in the task that first uses them.

**Known gap accepted deliberately:** there is no automated test for the `FALHOU` bubble rendering, because this repo has no DOM test environment. Task 7 Step 4 pins the palette in the CSS contract test and Task 11 Step 5 exercises the state by hand.
