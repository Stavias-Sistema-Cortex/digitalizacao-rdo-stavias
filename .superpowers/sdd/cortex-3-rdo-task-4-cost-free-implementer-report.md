# Cortex 3 RDO Task 4 — Cost-free Contract Implementer Report

## Outcome

New Cortex 3 RDO create/read/sync/UI contracts no longer accept, persist,
re-serialize, or display subjective service and collaborator cost fields.
Historical import normalization and legacy PostgreSQL columns remain intact as
audit evidence; no migration drops or rewrites those columns.

## TDD evidence

The new contract tests were run before production changes and failed on the
existing exposure:

- API: 3 of 4 assertions failed because request/response records and the
  operational service still contained cost fields. The legacy-evidence
  positive control passed.
- Web: the runtime source, historical-record mapping, and adversarial extra-key
  sanitization assertions all failed while the old fields could reach a draft
  or sync payload.

After implementation:

```text
mvn -f apps/api/pom.xml -Dtest='Rdo*Test,PrevisaoFinanceiraPayloadTest,PdorApplicationServiceTest,PdorControllerMockMvcTest' test
48 tests, 0 failures, 0 errors, BUILD SUCCESS

npm --prefix apps/web test
88 files, 459 tests passed

npm --prefix apps/web run lint
exit 0

npm --prefix apps/web run build
TypeScript, Vite, PWA, and StavIA source/dist boundary passed
```

## Contract changes

- Removed `custoRealizado`, `custoHora`, and response `custoTotal` from the RDO
  request/response records.
- Removed operational SQL reads/writes and ontology-memory event exposure for
  these fields.
- Removed their draft defaults, historical local-record mapping, TypeScript
  contract fields, labels, and inputs.
- Replaced generic object spreading in service/allocation local and sync
  payload builders with explicit allowlists. Legacy or adversarial extra cost
  keys are discarded both before local persistence and before synchronization.
- Kept historical XLSX normalization and V44 legacy columns so prior evidence
  remains readable without being mapped into the Cortex 3 operational model.

## Financeiro and PDOR boundary

The affected PDOR and Financeiro payload tests pass. Existing Financeiro code
still reads historical `execucao_servico_rdo.custo_realizado` and
`alocacao_colaborador.custo_total`; this task intentionally does not redesign
Financeiro or introduce the later revenue migration. Because new RDO writes no
longer populate these columns, subjective cost no longer enters Financeiro
through the Cortex 3 RDO path.

## Security and hardcoded review

Bounded review of the changed production scope found:

- no subjective cost identifier in the active RDO API/web runtime;
- no destructive `DROP COLUMN` for retained historical evidence;
- no added embedded secret, token, password, API key, host, or endpoint;
- no dynamic SQL; changed inserts remain parameterized;
- explicit payload allowlists prevent legacy/extraneous key mass assignment;
- no authentication, authorization, key access, or datasource configuration
  surface changed.

This is a changed-scope security review, not a claim of a new exhaustive
whole-system penetration test.
