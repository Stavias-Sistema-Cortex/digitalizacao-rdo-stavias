# Evidência — exportação RDO XLSX online e offline

Data da verificação: 22/07/2026.

## Contrato verificado

- O arquivo `RDO.xlsx` anexado pelo usuário, o template versionado do front-end e o template do servidor são idênticos byte a byte e têm SHA-256 `2a97db997d939b738146bad7c39428e38e159a6160f23afdf3297500fb2b8f87`.
- O RDO sincronizado e versionado usa somente o endpoint autenticado do servidor. Um `401`, `403` ou erro de resposta não aciona fallback silencioso.
- O RDO local, pendente ou sem versão do servidor usa somente o snapshot canônico do IndexedDB e o template precacheado pelo service worker.
- A exportação fica desabilitada com um motivo literal enquanto obra, identificação ou qualquer um dos cinco segmentos canônicos estiver incompleto.
- Respostas HTTP `200` com `Content-Type` HTML ou JSON são rejeitadas antes de criar um download.
- Nenhuma linha operacional parcial é descartada ou completada com zero; o exportador falha com um código exato.
- O arquivo resultante não contém fórmula, hyperlink, macro, relação externa, `customXml`, autor do template, PII ou os canários de segredo da fixture.

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

Resultado durável: [parity.json](rdo-export/parity.json) registra `equivalent: true`, duas sheets, 68 células comparadas por valor e tipo, todos os cinco segmentos com linhas não vazias, 149/52 mesclagens e as áreas de impressão `$A$1:$AJ$80` e `$A$2:$AH$70`.

Hashes dos XLSX gerados:

```text
ba78f8f411e46486a223e35a9d29a62a12e4b13310b9b1fb2f9f190d0497aee9  server.xlsx
5d546984cf2bfe497c673c5fe46488453ee1e8fc6a511890229aa4c7d0904223  offline.xlsx
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

Renders versionados:

- Servidor: [frente](rdo-export/server-page-1.png) e [verso](rdo-export/server-page-2.png)
- Offline: [frente](rdo-export/offline-page-1.png) e [verso](rdo-export/offline-page-2.png)

## Verificação offline/PWA

`npm --prefix apps/web run build` gerou 95 entradas de precache. `dist/sw.js` contém explicitamente:

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
91 arquivos, 489 testes aprovados

npm --prefix apps/web run lint
PASS, zero erro

npm --prefix apps/web run build
PASS; verificação da fronteira StavIA aprovada; PWA com 95 entradas

mvn -f apps/api/pom.xml -Dtest=RdoXlsxExportServiceTest test
11 testes do exportador aprovados

mvn -f apps/api/pom.xml -Dtest='<quatro testes do módulo rdos.export>' test
18 testes do módulo aprovados
```

O audit de dependências de produção reportou zero vulnerabilidades críticas/altas/moderadas e uma baixa transitiva existente em `dompurify` (`GHSA-c2j3-45gr-mqc4`). A adição direta de `fflate` reutiliza a resolução já presente no lockfile e não adicionou advisory. Nenhuma correção automática destrutiva foi aplicada.
