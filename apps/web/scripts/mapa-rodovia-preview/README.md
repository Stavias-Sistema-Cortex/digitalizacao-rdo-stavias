# Preview de geometria do Mapa da Rodovia

Harness de verificação visual, no mesmo espírito dos demais
`verify-*-geometry.mjs`: monta os componentes reais de
`src/features/obras/map` e `src/features/obras/trecho` com **fixtures de teste**
e mede a geometria nos três viewports exigidos pelo projeto.

As duas camadas de acesso a dados são substituídas por alias no
`vite.config.ts` deste diretório, e nada aqui entra no build de produção: o
`vite build` da PWA tem `apps/web/index.html` como única entrada.

Os dados exibidos são fixtures declarados em `fixtures.ts` e rotulados como
tais. Não representam obra, RDO ou produção real e não devem ser usados como
evidência operacional.

```bash
cd apps/web
node scripts/verify-mapa-rodovia-geometry.mjs
```

Os PNGs e o relatório JSON saem em `apps/web/.geometry-preview/`.
