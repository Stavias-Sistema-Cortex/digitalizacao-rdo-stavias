# Cortex 2.1 — Receita rastreável e navegação lateral

## Objetivo

Restaurar na branch do Cortex 2.1 a experiência financeira que está servida em
`localhost:5174`, usando o worktree `feat/financeiro-producao-receita` no commit
`c569c19` como fonte de verdade. A aba Financeiro volta a tratar somente da
Receita Operacional Rastreável. Em paralelo, a barra lateral recebe uma base em
degradê preto/verde e seus destaques deixam de ser pequenas barras amarelas ou
pretas para se tornarem frames completos ao redor do botão.

## Financeiro

### Escopo visível

- Exibir uma única área: `Rastreio de receita`.
- Remover da navegação do Financeiro as áreas Compras, Notas fiscais,
  Pagamentos e cobranças, Rateios, Centros de custo e Relatórios.
- Não apagar os serviços de backend dessas áreas; apenas retirar o workspace
  expandido da interface principal.
- Preservar a seleção e os filtros de período/obra usados pelo rastreio da
  versão 5174.

### Dados e ontologia

- Portar os endpoints e serviços de resultado operacional e rastreio de receita
  existentes nos commits `9059870`, `a881597` e `c569c19`.
- Consolidar produção, receita estimada, custo e margem a partir de dados reais
  dos RDOs.
- Separar explicitamente os estados de receita medida, aprovada, faturada e
  recebida.
- Exibir os tipos de serviço em execução com produção, obras contribuintes,
  receita estimada e margem.
- Manter o drawer de evidências com obra, RDOs de origem e valores canônicos.
- Quando a receita não for canonicamente calculável, exibir `Indisponível`; não
  preencher com zero nem inferir valores.
- Preservar as regras de autorização do backend da versão 5174.

### Compatibilidade com Cortex 2.1

- Aplicar os tokens atuais de preto estrutural, geometria de 4/6 px, Poppins e
  transições de 160 ms.
- Não reintroduzir a timeline ontológica antiga no Financeiro. A lista global de
  modificações continua exclusiva de `Home > Memória`; o drawer financeiro
  apresenta apenas evidências de composição da receita.
- Não alterar a geometria do botão StavIA.

## Barra lateral

### Fundo

- Trocar o fundo verde plano por um degradê escuro entre `#111312` e o verde
  institucional já existente.
- Manter contraste AA para textos e ícones claros nos estados normal, hover,
  ativo e desabilitado.
- Preservar os modos expandido, recolhido e responsivo.

### Frames dos botões

- Substituir a barra amarela lateral do item ativo por uma borda completa ao
  redor de todo o botão.
- O frame ativo usa amarelo institucional com contraste sobre o degradê.
- Hover e foco usam frames completos discretos; nenhum estado deve depender de
  uma faixa parcial preta ou amarela.
- Aplicar a regra também aos botões utilitários do rodapé e ao controle de
  recolher/expandir, sem mudar suas ações.
- Preservar o foco visível por teclado e `prefers-reduced-motion`.

## Verificação

- Testes de API para autorização e cálculo do rastreio de receita.
- Testes web para contrato da API e política visual da sidebar.
- Lint, suíte web completa e build de produção.
- Testes Java direcionados sob JDK 21.
- Auditoria de fonte garantindo que a navegação financeira contém apenas
  `Rastreio de receita`.
- Smoke em portas isoladas, validando `/financeiro`, o proxy da API e o limite
  de autenticação.
