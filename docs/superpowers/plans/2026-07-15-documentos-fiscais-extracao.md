# Documentos fiscais, extração e autoria — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:executing-plans` and `superpowers:test-driven-development`.

**Goal:** Permitir upload de documentos fiscais no fluxo da nota, extrair
campos reais de XML/PDF/imagem quando houver extrator disponível, preencher o
formulário com candidatos rastreáveis e preservar uploader, hashes, revisão e
confirmação humana.

**Architecture:** O arquivo passa primeiro pelo `StoredObjectService`. Um job
imutável referencia o objeto inspecionado e persiste candidatos por campo. XML
usa parser seguro; PDF usa camada de texto; imagem/PDF digitalizado usa um
provider OCR configurável e permanece `REVISAO_NECESSARIA` quando o provider
não estiver disponível. Nenhum extrator cria nota, lançamento ou autorização
fiscal sozinho. A UI confirma os candidatos, salva a nota pelo serviço atual e
então vincula o documento com hashes e autoria do job.

**Tech Stack:** Java 21, Spring MVC/JDBC, Flyway/MySQL 8, PDFBox, XML JAXP,
React 19, TypeScript, Vitest.

## Constraints

- Não editar V1–V36; usar V37+.
- Aceitar XML, PDF, JPEG, PNG, WebP e TIFF como principais.
- Não tratar ZIP como nota fiscal principal.
- DTD e entidades externas sempre desabilitados.
- O SHA-256 do cliente deve ser comparado com o hash do servidor.
- PDF/imagem sem evidência suficiente nunca recebem status `CONCLUIDO`.
- `autorizacaoFiscal` permanece `NAO_VERIFICADA` sem provider fiscal real.
- Toda mutação persiste ator, dispositivo, correlação e `clientMutationId`.
- O documento original é imutável; correções alteram somente candidatos ou a
  nota confirmada.

---

### Task 1: Persistir jobs, candidatos e autoria do vínculo

**Files:**

- Create: `apps/api/src/main/resources/db/migration/V37__finance_fiscal_document_extraction.sql`
- Create: `apps/api/src/test/java/com/projeto/cortex/financeiro/invoice/FinanceFiscalExtractionMigrationTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/pdor/FinanceFiscalExtractionMigrationMysqlIntegrationTest.java`

- [x] Escrever o contrato falhando para autoria, hashes, job e candidatos.
- [x] Implementar V37 com backfill seguro de vínculos legados.
- [x] Validar FKs, unicidade idempotente, checks de status e rollback MySQL.
- [x] Executar os testes com JDK 21 e MySQL real.
- [x] Commit: `feat(finance): persist fiscal extraction traceability`.

### Task 2: Inspecionar todos os formatos fiscais aceitos

**Files:**

- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/StoredObjectContentInspector.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/StorageProperties.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/storage/StoredObjectContentInspectorTest.java`

- [x] Escrever testes falhando para XML seguro e TIFF little/big endian.
- [x] Detectar XML, PDF, JPEG, PNG, WebP e TIFF por conteúdo.
- [x] Rejeitar MIME divergente e binário arbitrário; preservar ZIP somente
  como complemento genérico, nunca como entrada do extrator fiscal.
- [x] Executar regressão de storage.
- [x] Commit: `feat(storage): inspect fiscal document formats`.

### Task 3: Extrair candidatos com evidência e conferência matemática

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/invoice/extraction/FiscalExtractionDtos.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/invoice/extraction/FiscalDocumentExtractor.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/invoice/extraction/XmlFiscalExtractor.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/invoice/extraction/PdfTextFiscalExtractor.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/invoice/extraction/OcrFiscalExtractor.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/invoice/extraction/InvoiceExtractionCoordinator.java`
- Modify: `apps/api/pom.xml`
- Create: corresponding unit tests.

- [x] Testar XML NF-e com namespaces, chave, emitente, datas e totais.
- [x] Testar XXE, XML malformado, chave inválida e total divergente.
- [x] Testar PDF textual; preservar página/trecho/confiança.
- [x] Testar imagem e PDF digitalizado com provider OCR fake e indisponível.
- [x] Recalcular `bruto - desconto + acrescimo - retencoes`.
- [x] Nunca marcar autorização fiscal como confirmada.
- [x] Commit: `feat(finance): extract auditable fiscal candidates`.

### Task 4: Expor upload idempotente e vínculo integral

**Files:**

- Create: repository/service/controller under
  `apps/api/src/main/java/com/projeto/cortex/financeiro/invoice/extraction`.
- Modify: `FinanceInvoiceDtos.java`, `FinanceInvoiceService.java`,
  `FinanceInvoiceController.java`.
- Create/modify MockMvc, service and MySQL tests.

- [ ] Upload multipart exige `FINANCEIRO_OPERAR` no escopo.
- [ ] Persistir job/candidatos com ator do contexto, não do payload.
- [ ] Repetir `clientMutationId` retorna o mesmo resultado canônico.
- [ ] Vínculo exige objeto/job/obra/hash compatíveis.
- [ ] Persistir `enviado_por`, `confirmado_por`, dispositivo e correlação.
- [ ] Projetar `DOCUMENTO_FISCAL EXTRAIDO_DE STORED_OBJECT` e
  `NOTA_FISCAL DOCUMENTADA_POR DOCUMENTO_FISCAL`.
- [ ] Commit: `feat(finance): upload and link fiscal documents`.

### Task 5: Preencher o formulário a partir do arquivo

**Files:**

- Modify: `apps/web/src/features/financeiro/financeiro.types.ts`
- Modify: `apps/web/src/features/financeiro/financeiroApi.ts`
- Modify: `apps/web/src/features/financeiro/FinanceInvoicesPanel.tsx`
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.css`
- Modify/create Vitest files.

- [ ] Nova nota oferece `Enviar documento` e `Preencher manualmente`.
- [ ] `accept` inclui os formatos realmente suportados.
- [ ] Mostrar progresso, hash, extrator, confiança, avisos e revisão.
- [ ] Preencher somente candidatos retornados; fornecedor por CNPJ exato.
- [ ] Permitir editar todos os campos antes de confirmar.
- [ ] Salvar nota e vínculo; falha parcial mostra retomada sem duplicar nota.
- [ ] Anexar novos documentos também em notas existentes.
- [ ] Commit: `feat(web): ingest and review fiscal documents`.

### Task 6: Verificação integral

- [ ] Testes de migration e extração.
- [ ] MySQL real sem skips para V37 e vínculo.
- [ ] `mvnw clean test` com JDK 21.
- [ ] `npm test`, `npm run lint`, `npm run build`.
- [ ] `git diff --check` e revisão de dados falsos/status indevidos.
