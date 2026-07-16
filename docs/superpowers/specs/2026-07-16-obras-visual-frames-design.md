# Cortex 2.1 — refinamento visual de Obras e sidebar

## Objetivo

Refinar a página de Obras e a navegação lateral do Cortex 2.1 com uma linguagem institucional mais consistente: enquadramentos completos em vez de acentos parciais, amarelo reservado a informação e marca, e superfícies de dados com calor visual controlado.

## Escopo aprovado

### Sidebar

- Manter o gradiente existente entre `#111312` e o verde institucional `#124e4a`.
- Remover o amarelo do frame dos botões laterais.
- Usar borda transparente no estado neutro, borda branca translúcida no hover e frame branco translúcido completo no estado ativo.
- Preservar os modos expandido, recolhido e mobile, incluindo foco visível e transições funcionais.

### Página de Obras

- Remover apenas o botão verde `StavIA` do cabeçalho da obra.
- Preservar o contexto da obra para o launcher global da StavIA; a remoção é somente do atalho local.
- Substituir a barra amarela parcial do item selecionado por um frame preto completo.
- Substituir as bordas pretas apenas no topo dos painéis de detalhes e PDOR por frames pretos completos.
- Manter o link `Ver na Memória` e as demais ações existentes.

### Status da obra

- Renderizar `ATIVA` como texto preto, sem pill verde.
- Posicionar uma faixa amarela de marca-texto atrás do texto com pseudo-elemento, leve irregularidade e geometria compacta.
- Manter contraste legível e evitar sombra, brilho ou animação decorativa.

### Cartões de dados

- Aplicar a todos os itens de `.obras-facts`, incluindo Contrato, Valor contratual, Localização, Rodovia, Coordenadas e Atualizado em.
- Usar um gradiente diagonal de branco para amarelo-claro, com predominância de branco.
- Usar borda quente discreta, texto escuro e raio de `3px`.
- Não adicionar elevação, hover cenográfico ou informação fictícia.

## Direção visual

- **Paleta:** preto `#111312`, verde `#124e4a`, amarelo `#f2c800`, amarelo-claro `#fff3b0`, branco `#ffffff` e cinza de borda institucional.
- **Tipografia:** preservar a família atual; status, valores e títulos usam peso moderado, sem aumentar o negrito geral.
- **Assinatura:** o marca-texto amarelo do status é o único gesto expressivo. Os demais elementos permanecem geométricos e sóbrios.
- **Movimento:** transições apenas de `background-color`, `border-color` e `color`, respeitando `prefers-reduced-motion`.

## Arquivos previstos

- `apps/web/src/features/obras/ObrasPage.tsx`
- `apps/web/src/index.css`
- `apps/web/src/features/home/institutionalUiPolicy.test.ts`
- `apps/web/src/uiPolish.test.ts`, caso o contrato visual compartilhado precise ser ajustado

## Critérios de aceitação

1. Não existe botão local `StavIA` na página de Obras.
2. O launcher global continua recebendo o contexto da obra selecionada.
3. Nenhum botão lateral ativo usa frame amarelo.
4. Sidebar e item ativo de Obras usam frames completos, sem pseudo-elementos de barra lateral.
5. Os painéis de detalhes e PDOR têm frame preto em todo o perímetro.
6. O status `ATIVA` aparece em preto sobre faixa amarela de marca-texto.
7. Todos os cartões de fatos usam gradiente branco–amarelo-claro.
8. Lint, testes web, build e smoke da rota `/obras` passam.

## Fora de escopo

- Alterar comportamento, respostas ou launcher global da StavIA.
- Modificar dados, API ou ontologia de Obras.
- Redesenhar formulários de criação ou gestão administrativa de obras.
- Introduzir novas cores, fontes ou dependências de UI.
