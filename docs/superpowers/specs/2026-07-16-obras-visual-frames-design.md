# Cortex 2.1 — molduras institucionais, Obras e login

## Objetivo

Eliminar faixas pretas ou amarelas que aparecem apenas no topo de cartões, convertendo-as em molduras completas; restaurar a barra vertical amarela da navegação; refinar Obras; e transformar o login em uma superfície séria de acesso institucional.

## Escopo aprovado

### Molduras completas

- Todo cartão, painel ou bloco que use faixa preta/amarela grossa apenas no topo passa a usar a mesma cor em todo o perímetro.
- A regra cobre Home, RDO, Obras, Integrações, Financeiro e desbloqueio offline.
- Molduras grandes usam espessura equilibrada de `2px`; cartões métricos preservam alternância preta/amarela com `2px` completos.
- Separadores internos, títulos, indicadores, spinner e elementos que não são superfícies de conteúdo não são convertidos.
- Raios permanecem pequenos e sombras continuam removidas.

### Sidebar

- Manter o gradiente existente entre `#111312` e o verde institucional `#124e4a`.
- Remover o frame amarelo completo do item ativo.
- Restaurar o marcador vertical amarelo à esquerda do botão selecionado.
- Manter borda transparente, fundo branco discreto e foco amarelo acessível.
- Preservar os modos expandido, recolhido e mobile.

### Página de Obras

- Remover somente o botão verde `StavIA` do cabeçalho da obra.
- Preservar `setStaviaContext` para o launcher global da StavIA.
- Converter a barra amarela parcial do item selecionado em moldura preta completa.
- Converter as faixas pretas dos painéis de detalhes e PDOR em molduras pretas completas.
- Manter o link `Ver na Memória` e as demais ações.

### Status da obra

- Renderizar `ATIVA` como texto preto sem pill verde.
- Posicionar uma faixa amarela de marca-texto atrás do texto com pseudo-elemento, leve irregularidade e geometria compacta.
- Não usar brilho, sombra ou movimento decorativo.

### Cartões de dados de Obras

- Aplicar a Contrato, Valor contratual, Localização, Rodovia, Coordenadas e Atualizado em.
- Usar gradiente diagonal com predominância branca e término amarelo-claro `#fff3b0`.
- Usar borda quente discreta, texto escuro e raio de `3px`.

### Login institucional

- Remover visualmente a fotografia do canteiro e o véu fotográfico.
- Usar o mesmo gradiente preto–verde da sidebar como fundo integral.
- Criar composição desktop em duas áreas: identificação institucional à esquerda e formulário branco à direita.
- Identificação: tile Stavias pequeno, rótulo `Sistema Córtex`, título `Acesso institucional` e texto objetivo de ambiente operacional restrito.
- Formulário: título `Entrar no sistema`, instrução do CPF, campo, ação amarela, passkey secundária, estados offline/erro e nota de suporte.
- Card branco com moldura clara, raio de `4px`, sem blur, glassmorphism ou sombra pesada.
- Mobile empilha identificação e formulário sem perder contraste ou ordem de foco.
- Preservar autenticação CPF, passkey, validação, mensagens e acessibilidade existentes.

## Direção visual

- **Paleta:** `#111312`, `#124e4a`, `#f2c800`, `#fff3b0`, `#ffffff` e cinzas institucionais.
- **Tipografia:** manter Poppins; títulos em peso `600`, texto em `400`/`500`, dados com numerais tabulares.
- **Assinatura:** gradiente preto–verde e marca-texto amarelo do status; o restante é geométrico, silencioso e formal.
- **Movimento:** transições funcionais de cor/borda e entrada curta opcional, sempre desativada em `prefers-reduced-motion`.

## Inventário de superfícies com faixa grossa

- `.home-obra-card`
- `.rdo-command-band`
- `.metric-card` e sua alternância amarela
- `.rdo-memory-link-panel`
- `.obras-detail`
- `.obras-pdor`
- `.integracoes-table-card`
- `.integracoes-report`
- `.finance-operational-result`
- `.offline-unlock__card`

## Arquivos previstos

- `apps/web/src/index.css`
- `apps/web/src/features/obras/ObrasPage.tsx`
- `apps/web/src/features/auth/LoginPage.tsx`
- `apps/web/src/features/auth/LoginPage.css`
- `apps/web/src/features/integracoes/IntegracoesPage.css`
- `apps/web/src/features/financeiro/FinanceiroPage.css`
- `apps/web/src/features/auth/OfflineUnlockPage.css`
- `apps/web/src/features/home/institutionalUiPolicy.test.ts`
- `apps/web/src/uiPolish.test.ts`

## Critérios de aceitação

1. Nenhum cartão do inventário mantém faixa preta/amarela grossa apenas no topo.
2. Os botões da sidebar usam barra vertical amarela ao selecionar, sem frame amarelo.
3. Não existe botão local `StavIA` em Obras, mas o contexto global continua atualizado.
4. O item ativo de Obras e os painéis da obra têm molduras completas.
5. `ATIVA` aparece em preto sobre marca-texto amarelo.
6. Os cartões de fatos usam gradiente branco–amarelo-claro.
7. O login usa fundo preto–verde, composição formal e card branco sem fotografia ou blur.
8. Login CPF, passkey, offline, erros, foco e responsividade continuam funcionais.
9. Lint, testes web, build e smoke das rotas `/login`, `/obras` e `/` em `5177` passam.

## Fora de escopo

- Alterar API, autenticação, dados ou ontologia.
- Remover ou redesenhar o launcher global da StavIA.
- Converter separadores internos e indicadores que não sejam cartões.
- Introduzir nova fonte ou dependência de UI.
