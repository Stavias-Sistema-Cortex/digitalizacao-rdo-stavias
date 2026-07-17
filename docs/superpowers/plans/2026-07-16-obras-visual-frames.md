# Obras Visual Frames Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aplicar frames institucionais completos à sidebar e à página de Obras, remover o atalho local StavIA e introduzir o status com marca-texto e os cartões branco–amarelo aprovados.

**Architecture:** Manter a estrutura atual de `ObrasPage` e alterar somente o markup necessário para o status e a ação StavIA. Consolidar a precedência visual na camada final de `index.css`, onde o Cortex 2.1 já sobrescreve os estilos legados, e proteger o resultado com o teste de política visual existente.

**Tech Stack:** React 19, TypeScript, CSS compartilhado, Vitest, ESLint e Vite.

## Global Constraints

- Manter o gradiente da sidebar entre `#111312` e `#124e4a`.
- Não usar frame amarelo nos botões laterais.
- Preservar o launcher global e `setStaviaContext` para a obra selecionada.
- Não alterar API, dados ou ontologia de Obras.
- Preservar Poppins, os tokens de raio existentes e `prefers-reduced-motion`.

---

### Task 1: Contrato visual de Obras e sidebar

**Files:**
- Modify: `apps/web/src/features/home/institutionalUiPolicy.test.ts`
- Modify: `apps/web/src/features/obras/ObrasPage.tsx`
- Modify: `apps/web/src/index.css`

**Interfaces:**
- Consumes: `useStaviaLauncher(): { setStaviaContext(...) }`, `.sidebar-nav-item.active`, `.obras-list-item.active`, `.obras-detail`, `.obras-pdor`, `.obras-facts`.
- Produces: classe `.obras-status-marker` e contratos estáticos que impedem a volta do botão local StavIA e de acentos parciais.

- [ ] **Step 1: Escrever os testes que descrevem o resultado aprovado**

Adicionar a leitura de `ObrasPage.tsx` e três casos ao teste de política:

```ts
const obrasPage = readFileSync(
  resolve(process.cwd(), "src/features/obras/ObrasPage.tsx"),
  "utf8",
);

it("keeps the global StavIA context without a local Obras launcher", () => {
  expect(obrasPage).toContain("setStaviaContext");
  expect(obrasPage).not.toContain("openStavia");
  expect(obrasPage).not.toContain("obras-stavia-button");
});

it("uses complete neutral frames instead of partial yellow accents", () => {
  expect(css).not.toMatch(
    /\.sidebar-nav-item\.active\s*\{[^}]*border[^;}]*var\(--color-brand-yellow\)/s,
  );
  expect(css).not.toContain(".sidebar-nav-item.active::before");
  expect(css).not.toContain(".obras-list-item.active::before");
  expect(css).toMatch(
    /\.obras-list-item\.active\s*\{[^}]*border:\s*1px solid var\(--color-ink\)/s,
  );
});

it("marks active works and warms every work fact card", () => {
  expect(obrasPage).toContain('className="obras-status-marker"');
  expect(css).toContain(".obras-status-marker::before");
  expect(css).toMatch(
    /\.obras-facts div\s*\{[^}]*linear-gradient\([^)]*#fff[^)]*#fff3b0/s,
  );
});
```

- [ ] **Step 2: Executar o teste e confirmar o RED correto**

Run:

```bash
cd apps/web
npm test -- --run src/features/home/institutionalUiPolicy.test.ts
```

Expected: FAIL porque `openStavia` e `.obras-stavia-button` ainda existem, a sidebar ainda usa frame amarelo, a lista ainda usa pseudo-barra e os novos estilos de status/gradiente ainda não existem.

- [ ] **Step 3: Remover o atalho local e criar o status dedicado**

Em `ObrasPage.tsx`, manter o hook global e remover apenas `openStavia`:

```tsx
const { setStaviaContext } = useStaviaLauncher();

<span className="obras-status-marker">
  {focusedObra.status}
</span>
```

Remover integralmente:

```tsx
<button
  type="button"
  className="obras-stavia-button"
  onClick={() => openStavia({ obraId: focusedObra.id })}
>
  StavIA
</button>
```

- [ ] **Step 4: Implementar a camada visual final**

Em `index.css`, substituir o frame amarelo ativo da sidebar por um frame neutro:

```css
.sidebar-nav-item.active {
  border-color: rgb(255 255 255 / 52%);
  background: rgb(255 255 255 / 6%);
  font-weight: 600;
}
```

Na camada final do Cortex 2.1, remover as regras `::before` da lista e adicionar:

```css
.obras-list-item.active {
  border: 1px solid var(--color-ink);
  background: #fff;
  color: var(--color-ink);
}

.obras-detail,
.obras-pdor {
  border: 1px solid var(--color-ink);
}

.obras-status-marker {
  position: relative;
  isolation: isolate;
  display: inline-block;
  color: var(--color-ink);
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.05em;
}

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
  border-radius: 3px;
  background: linear-gradient(135deg, #fff 18%, #fffdf2 62%, #fff3b0 100%);
}
```

- [ ] **Step 5: Executar o teste e confirmar o GREEN**

Run:

```bash
cd apps/web
npm test -- --run src/features/home/institutionalUiPolicy.test.ts
```

Expected: 1 arquivo e todos os casos aprovados.

- [ ] **Step 6: Commitar o ciclo TDD**

```bash
git add apps/web/src/features/home/institutionalUiPolicy.test.ts apps/web/src/features/obras/ObrasPage.tsx apps/web/src/index.css
git commit -m "style(web): refine Obras frames and status"
```

### Task 2: Verificação completa e runtime em 5177

**Files:**
- Verify: `apps/web/src/features/obras/ObrasPage.tsx`
- Verify: `apps/web/src/index.css`

**Interfaces:**
- Consumes: rota Vite `/obras` e API local já configurada no worktree.
- Produces: evidência de lint, testes, build, requisitos estáticos e HTTP 200 em `127.0.0.1:5177/obras`.

- [ ] **Step 1: Executar gates web completos**

```bash
cd apps/web
npm run lint
npm test -- --run
npm run build
```

Expected: lint sem erros, 54 arquivos de teste aprovados e build Vite concluído.

- [ ] **Step 2: Auditar os requisitos no fonte final**

```bash
rg -n "openStavia|obras-stavia-button|sidebar-nav-item.active::before|obras-list-item.active::before" apps/web/src/features/obras/ObrasPage.tsx apps/web/src/index.css
rg -n "obras-status-marker|linear-gradient\(135deg, #fff|border: 1px solid var\(--color-ink\)" apps/web/src/features/obras/ObrasPage.tsx apps/web/src/index.css
git diff --check
```

Expected: a primeira busca não retorna ocorrências; a segunda confirma status, gradiente e frames; `git diff --check` sai com código 0.

- [ ] **Step 3: Validar o runtime servido ao usuário**

```bash
curl -sS -o /tmp/cortex-obras-5177.html -w '%{http_code}\n' http://127.0.0.1:5177/obras
open http://127.0.0.1:5177/obras
```

Expected: HTTP 200 e navegador aberto na rota de Obras.

- [ ] **Step 4: Preservar a branch isolada**

```bash
git status --short --branch
```

Expected: branch `feat/cortex-2-1-memory-ui` limpa, sem merge ou push automático.
