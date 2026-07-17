# Login Stavias Córtex — lockup oficial

## Objetivo

Substituir, no painel institucional da tela de login, a composição atual formada pelo tile amarelo e pelo texto `STAVIAS` pelo lockup oficial completo `STAVIAS | CORTEX` já disponível no repositório.

## Ativo e composição

- Fonte da marca: `apps/web/src/assets/login/cortex-logo.png`.
- O componente usará uma única imagem, sem remontar letras ou separador em CSS.
- O lockup ocupará a posição atual da marca, acima do divisor horizontal.
- O texto estrutural `Sistema Córtex` abaixo do divisor permanece inalterado.

## Escala responsiva

- Desktop: largura fluida com limite aproximado de `440px`, mantendo a proporção original.
- Mobile: largura limitada ao espaço interno disponível, sem corte, distorção ou rolagem horizontal.
- A imagem manterá fundo transparente e não receberá moldura, sombra ou brilho.

## Acessibilidade

- O texto alternativo identificará a marca como `Stavias Córtex`.
- A imagem continuará não arrastável e sem comportamento interativo.

## Verificação

- Um teste de regressão confirmará o uso de `cortex-logo.png` e a remoção da composição antiga no login.
- O login será inspecionado em viewport desktop e mobile.
- Lint, testes do frontend e build de produção serão executados antes da conclusão.

## Fora de escopo

- Alterar textos, autenticação, cores, estrutura dos painéis ou a marca na sidebar.
- Redesenhar ou converter o ativo oficial para outro formato.
