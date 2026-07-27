# Evidência — exportação RDO XLSX online e offline

Verificação original: 22/07/2026. Regeneração final: 23/07/2026.

Há duas afirmações distintas: a identidade byte a byte dos templates abaixo é
evidência durável; a paridade entre arquivos **gerados** foi refeita a partir do
tree de integração de 23/07/2026.

## Contrato verificado

- O arquivo `RDO.xlsx` anexado pelo usuário, o template versionado do front-end e o template do servidor são idênticos byte a byte e têm SHA-256 `2a97db997d939b738146bad7c39428e38e159a6160f23afdf3297500fb2b8f87`.
- O RDO sincronizado e versionado usa somente o endpoint autenticado do servidor. Um `401`, `403` ou erro de resposta não aciona fallback silencioso.
- O RDO local, pendente ou sem versão do servidor usa somente o snapshot canônico do IndexedDB e o template precacheado pelo service worker.
- A exportação fica desabilitada com um motivo literal enquanto obra, identificação ou qualquer um dos cinco segmentos canônicos estiver incompleto.
- Respostas HTTP `200` com `Content-Type` HTML ou JSON são rejeitadas antes de criar um download.
- Nenhuma linha operacional parcial é descartada ou completada com zero; o exportador falha com um código exato.
- O arquivo resultante não contém fórmula, hyperlink, macro, relação externa, `customXml`, autor do template, PII ou os canários de segredo da fixture.

O contexto canônico que alimenta a exportação de um RDO é sustentado pelas
migrações PostgreSQL V48/V50/V55/V57: contexto por obra, escopo e autorização,
recibo canônico e integridade referencial da proveniência. Citar apenas
V48/V50 omite as garantias de recibo e vínculo acrescentadas em V55/V57.

### Paridade fail-closed dos limites de impressão

Um vetor idêntico Java/TypeScript cobre os quatro campos de KM (12 codepoints), material original (24), número do trecho (12), pista (16), faixa (16) e ordem de serviço (30). Para cada campo, os dois exportadores:

- aceitam ASCII exatamente no limite;
- aceitam emoji astral exatamente no limite, provando contagem por codepoint e não por UTF-16;
- rejeitam `limite + 1`, LF e CR;
- retornam a mesma mensagem `O conteúdo de <campo> não permanece legível no RDO (limite de <n> caracteres em uma linha); nenhum conteúdo foi truncado.`; o TypeScript usa o código estável `RDO_EXPORT_PRINT_OVERFLOW`.

## Paridade Java/TypeScript

A mesma fixture completa foi exportada por Java e TypeScript. Ela inclui clima, pluviometria, interdição, horários, dois grupos de mão de obra, equipamento, três movimentos de material, controle geométrico, serviço, observações, assinaturas e a entrada maliciosa `@cmd` + email + CPF + Bearer. Os valores sensíveis aparecem apenas redigidos.

Artefatos temporários reproduzíveis:

- `apps/api/target/rdo-xlsx-parity/server.xlsx`
- `apps/web/target/rdo-xlsx-parity/offline.xlsx`
- `apps/web/target/rdo-xlsx-parity/parity.json`

Comandos:

```bash
mvn -f apps/api/pom.xml \
  -Dtest='com.projeto.cortex.rdos.export.RdoXlsxExportServiceTest#emitsCompleteSanitizedParityFixtureForOfflineComparison' \
  -Dcortex.rdo.parity.output="$PWD/apps/api/target/rdo-xlsx-parity/server.xlsx" test

CORTEX_RDO_OFFLINE_OUTPUT="$PWD/apps/web/target/rdo-xlsx-parity/offline.xlsx" \
  npm --prefix apps/web test -- --run \
  src/features/rdos/export/rdoWorkbookMapping.test.ts \
  -t 'emits the Java parity fixture'

node apps/web/scripts/compare-rdo-xlsx.mjs \
  apps/api/target/rdo-xlsx-parity/server.xlsx \
  apps/web/target/rdo-xlsx-parity/offline.xlsx \
  apps/web/target/rdo-xlsx-parity/parity.json
```

O comparador foi reexecutado em 23/07 e registrou `equivalent: true`, duas
sheets, 68 células comparadas por valor e tipo, todos os cinco segmentos com
linhas não vazias, 149/52 mesclagens e as áreas de impressão
`$A$1:$AJ$80` e `$A$2:$AH$70`.

Hashes dos XLSX gerados:

```text
e6da92487146f58fd1b9eac369bfac1c5c4ece92705727f6102fd5115daa5d2a  server.xlsx
e18f9e8426635aacfead25857a9e96c9e78be156616e29d7bece48c0b56b5a11  offline.xlsx
```

Os hashes binários diferem porque Apache POI e o patch OOXML do navegador serializam metadados e ZIP de formas distintas. A comparação semântica acima cobre nomes, valores, tipos, contagens, mesclagens e impressão.

## Render visual

Render reproduzido com LibreOffice headless e Poppler:

```bash
SOFFICE=/Users/joaolucas/.cache/codex-runtimes/codex-primary-runtime/dependencies/bin/override/soffice
"$SOFFICE" --headless --convert-to pdf \
  --outdir apps/web/target/rdo-xlsx-parity/render-final-server \
  apps/api/target/rdo-xlsx-parity/server.xlsx
"$SOFFICE" --headless --convert-to pdf \
  --outdir apps/web/target/rdo-xlsx-parity/render-final-offline \
  apps/web/target/rdo-xlsx-parity/offline.xlsx
pdftoppm -png -r 120 server.pdf server-page
pdftoppm -png -r 120 offline.pdf offline-page
```

Ambos os PDFs têm duas páginas A4. A inspeção visual encontrou e corrigiu uma regressão que a primeira comparação estrutural não revelava: a limpeza das células removia IDs de estilo do template, apagando bordas e alinhamentos no offline. O exportador agora preserva o estilo de toda célula limpa e mantém o alinhamento do título; há um teste automatizado específico para essa propriedade.

Na regeneração de 23/07, Artifact Tool importou e renderizou as duas sheets do
arquivo anexado, do XLSX servidor e do XLSX offline. As seis renders foram
inspecionadas; servidor e offline preservam o mesmo conteúdo, estrutura,
bordas, logo, assinaturas e áreas operacionais. Os três workbooks reportaram
zero fórmulas e zero células com erro de fórmula.

Renders versionados:

- Servidor: [frente](rdo-export/server-page-1.png) e [verso](rdo-export/server-page-2.png)
- Offline: [frente](rdo-export/offline-page-1.png) e [verso](rdo-export/offline-page-2.png)

## Verificação offline/PWA

`npm --prefix apps/web run build` gerou 99 entradas de precache. `dist/sw.js` contém explicitamente:

```text
assets/RDO-v1-CHRAOGWD.xlsx
assets/exportRdoWorkbook-B1N50ppy.js
assets/rdoWorkbookMapping-C5sqrHIe.js
assets/xlsx-CKkngM-o.js
```

O template emitido em `dist/assets/RDO-v1-CHRAOGWD.xlsx` preserva o SHA-256 revisado. O pacote offline também preserva `xl/media`, `xl/drawings` e `xl/printerSettings` das duas folhas.

## Testes e segurança

Comandos e resultados:

```text
npm --prefix apps/web test -- --run
128 arquivos, 652 testes aprovados

npm --prefix apps/web run lint
PASS, zero erro

npm --prefix apps/web run build
PASS; verificação da fronteira StavIA aprovada; PWA com 99 entradas

mvn -f apps/api/pom.xml -Dtest=RdoXlsxExportServiceTest test
11 testes do exportador aprovados

mvn -f apps/api/pom.xml -Dtest='<quatro testes do módulo rdos.export>' test
18 testes do módulo aprovados
```

Na revisão atual, `npm audit --omit=dev` reportou zero vulnerabilidades. O OWASP
Dependency-Check analisou 100 dependências da API e reportou zero
vulnerabilidades, zero achados CVSS >= 7 e zero supressões.
