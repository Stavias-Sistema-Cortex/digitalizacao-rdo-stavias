# Institutional Frames and Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Converter faixas grossas parciais em molduras completas, restaurar a barra vertical da sidebar, refinar Obras e implantar um login institucional preto–verde em `5177`.

**Architecture:** Preservar os componentes e fluxos de dados existentes. Usar testes de política de fonte/CSS para cobrir o vocabulário visual transversal, alterar o mínimo de markup em Obras e Login, e consolidar as molduras nos arquivos CSS que já são responsáveis por cada superfície.

**Tech Stack:** React 19, TypeScript, Poppins local, CSS, Vitest, ESLint e Vite.

## Global Constraints

- Não alterar API, autenticação, ontologia ou persistência.
- Sidebar e login usam `linear-gradient(155deg, #111312 0%, #123a37 46%, #124e4a 100%)`.
- Sidebar selecionada usa barra vertical amarela, não frame amarelo.
- Cartões com faixa preta/amarela grossa passam a ter moldura completa de `2px`.
- Separadores internos, títulos, spinner e launcher global StavIA ficam fora da conversão.
- Login não usa fotografia, blur, backdrop-filter ou glassmorphism.

---

### Task 1: Contratos visuais em RED

**Files:**
- Modify: `apps/web/src/features/home/institutionalUiPolicy.test.ts`
- Modify: `apps/web/src/uiPolish.test.ts`

**Interfaces:**
- Consumes: arquivos TSX/CSS como texto.
- Produces: contratos para sidebar, molduras, Obras e Login.

- [ ] **Step 1: Adicionar leituras das superfícies**

```ts
const obrasPage = readFileSync(resolve(process.cwd(), "src/features/obras/ObrasPage.tsx"), "utf8");
const loginPage = readFileSync(resolve(process.cwd(), "src/features/auth/LoginPage.tsx"), "utf8");
const loginCss = readFileSync(resolve(process.cwd(), "src/features/auth/LoginPage.css"), "utf8");
const integrationsCss = readFileSync(resolve(process.cwd(), "src/features/integracoes/IntegracoesPage.css"), "utf8");
const financeCss = readFileSync(resolve(process.cwd(), "src/features/financeiro/FinanceiroPage.css"), "utf8");
const offlineCss = readFileSync(resolve(process.cwd(), "src/features/auth/OfflineUnlockPage.css"), "utf8");
```

- [ ] **Step 2: Adicionar testes específicos**

```ts
it("restores the vertical selection bar without a yellow sidebar frame", () => {
  expect(css).toContain(".sidebar-nav-item.active::before");
  expect(css).toMatch(/\.sidebar-nav-item\.active::before\s*\{[^}]*background:\s*var\(--color-brand-yellow\)/s);
  expect(css).not.toMatch(/\.sidebar-nav-item\.active\s*\{[^}]*border[^;}]*var\(--color-brand-yellow\)/s);
});

it("replaces thick top accents with complete frames", () => {
  expect(css).not.toMatch(/border-top:\s*[2-9]px solid var\(--color-ink\)/);
  expect(integrationsCss).not.toMatch(/border-top:\s*[2-9]px/);
  expect(financeCss).not.toMatch(/border-top:\s*[2-9]px solid var\(--finance-ink\)/);
  expect(offlineCss).not.toMatch(/border-top:\s*[2-9]px/);
  expect(css).toMatch(/\.home-obra-card\s*\{[^}]*border:\s*2px solid var\(--color-ink\)/s);
});

it("keeps global StavIA context but removes the local Obras launcher", () => {
  expect(obrasPage).toContain("setStaviaContext");
  expect(obrasPage).not.toContain("openStavia");
  expect(obrasPage).not.toContain("obras-stavia-button");
});

it("uses the approved Obras status and fact treatment", () => {
  expect(obrasPage).toContain('className="obras-status-marker"');
  expect(css).toContain(".obras-status-marker::before");
  expect(css).toMatch(/\.obras-facts div\s*\{[^}]*linear-gradient\([^)]*#fff[^)]*#fff3b0/s);
  expect(css).not.toContain(".obras-list-item.active::before");
});

it("uses a formal photo-free black-green login", () => {
  expect(loginPage).not.toContain("canteiroBackdrop");
  expect(loginPage).not.toContain("login__backdrop");
  expect(loginPage).toContain("Acesso institucional");
  expect(loginPage).toContain("Entrar no sistema");
  expect(loginCss).toMatch(/\.cortex-login\s*\{[^}]*linear-gradient\([^)]*#111312[^)]*#124e4a/s);
  expect(loginCss).toMatch(/\.login__card\s*\{[^}]*background:\s*#fff/s);
  expect(loginCss).not.toContain("backdrop-filter");
});
```

Atualizar em `uiPolish.test.ts` o contrato da métrica:

```ts
expect(metricCard).toContain("border: 2px solid var(--color-ink);");
expect(metricCard).not.toContain("border-top:");
```

- [ ] **Step 3: Executar RED**

```bash
cd apps/web
npm test -- --run src/features/home/institutionalUiPolicy.test.ts src/uiPolish.test.ts
```

Expected: FAIL pelos comportamentos ainda ausentes; não por erro de sintaxe.

### Task 2: Sidebar, molduras e Obras em GREEN

**Files:**
- Modify: `apps/web/src/index.css`
- Modify: `apps/web/src/features/integracoes/IntegracoesPage.css`
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.css`
- Modify: `apps/web/src/features/auth/OfflineUnlockPage.css`
- Modify: `apps/web/src/features/obras/ObrasPage.tsx`

**Interfaces:**
- Consumes: classes existentes das superfícies.
- Produces: molduras completas, sidebar vertical e `.obras-status-marker`.

- [ ] **Step 1: Restaurar a barra vertical da sidebar**

```css
.sidebar-nav-item.active {
  border-color: transparent;
  background: rgb(255 255 255 / 10%);
  font-weight: 600;
}

.sidebar-nav-item.active::before {
  content: "";
  position: absolute;
  top: 20%;
  bottom: 20%;
  left: 0;
  width: 3px;
  background: var(--color-brand-yellow);
}
```

- [ ] **Step 2: Converter o inventário de faixas em molduras**

Aplicar `border: 2px solid` na cor da faixa a `.home-obra-card`, `.rdo-command-band`, `.metric-card`, `.rdo-memory-link-panel`, `.obras-detail`, `.obras-pdor`, `.integracoes-table-card`, `.integracoes-report`, `.finance-operational-result` e `.offline-unlock__card`. Em `.metric-card:nth-child(2n)`, usar `border-color: var(--color-brand-yellow)`.

- [ ] **Step 3: Refinar Obras**

```tsx
const { setStaviaContext } = useStaviaLauncher();

<span className="obras-status-marker">{focusedObra.status}</span>
```

Remover o botão `.obras-stavia-button`, remover `.obras-list-item.active::before`, enquadrar o item ativo em preto e aplicar:

```css
.obras-status-marker::before {
  content: "";
  position: absolute;
  z-index: -1;
  inset: 48% -5px -2px;
  background: var(--color-brand-yellow);
  transform: rotate(-1deg);
}

.obras-facts div {
  border: 1px solid #eadf9f;
  background: linear-gradient(135deg, #fff 18%, #fffdf2 62%, #fff3b0 100%);
}
```

### Task 3: Login institucional em GREEN

**Files:**
- Modify: `apps/web/src/features/auth/LoginPage.tsx`
- Modify: `apps/web/src/features/auth/LoginPage.css`

**Interfaces:**
- Consumes: handlers, estados, campo CPF e passkey existentes.
- Produces: `.login__identity`, `.login__classification`, `.login__card-header` e layout institucional responsivo.

- [ ] **Step 1: Remover fotografia e estruturar duas áreas**

Remover import/elemento de `canteiroBackdrop` e `login__tint`. Manter um único `h1` visível `Acesso institucional`; criar identidade com `Sistema Córtex`, cópia operacional e nota de ambiente monitorado. No card branco, usar `h2` `Entrar no sistema`, preservando formulário e estados.

- [ ] **Step 2: Substituir o CSS promocional por CSS institucional**

Usar gradiente integral no `.cortex-login`, grid `minmax(0, 1fr) minmax(380px, 460px)` no `.login__stage`, identidade escura, `.login__card { background: #fff; border-radius: 4px; }`, controles com raio de `4px`, foco amarelo/preto, ausência de blur e empilhamento abaixo de `760px`.

- [ ] **Step 3: Executar GREEN e refatorar**

```bash
cd apps/web
npm test -- --run src/features/home/institutionalUiPolicy.test.ts src/uiPolish.test.ts src/features/auth/LoginPage.authPolicy.test.ts
```

Expected: todos os casos aprovados.

- [ ] **Step 4: Commitar o ciclo TDD**

```bash
git add apps/web/src
git commit -m "style(web): institutionalize frames and login"
```

### Task 4: Verificação e runtime em 5177

**Files:**
- Verify: `apps/web/src`

**Interfaces:**
- Produces: evidência de lint, testes, build e HTTP 200.

- [ ] **Step 1: Executar gates completos**

```bash
cd apps/web
npm run lint
npm test -- --run
npm run build
```

- [ ] **Step 2: Auditar requisitos**

```bash
rg -n "border-top:\s*[2-9]px solid" apps/web/src --glob '*.css'
rg -n "canteiroBackdrop|login__backdrop|obras-stavia-button" apps/web/src/features/auth/LoginPage.tsx apps/web/src/features/obras/ObrasPage.tsx
rg -n "sidebar-nav-item.active::before|obras-status-marker|#fff3b0" apps/web/src/index.css
git diff --check
```

Expected: somente a borda tracejada tipográfica da StavIA pode permanecer na primeira busca; termos removidos não aparecem; elementos aprovados aparecem.

- [ ] **Step 3: Validar rotas servidas**

```bash
for path in login obras ''; do curl -sS -o /dev/null -w "$path=%{http_code}\n" "http://127.0.0.1:5177/${path}"; done
open http://127.0.0.1:5177/login
```

Expected: HTTP 200 nas três rotas e login aberto no navegador.

- [ ] **Step 4: Preservar branch isolada**

```bash
git status --short --branch
```

Expected: `feat/cortex-2-1-memory-ui` limpa, sem merge/push automático.
