# Cortex 3 completion evidence

Final validation: 2026-07-23.

## Delivered product contract

| Area | Delivered behavior | Durable evidence |
| --- | --- | --- |
| StavIA and ontology | The assistant runtime and launcher are retired from source, database, and production bundle. The ontology, knowledge graph, operational ledger, full Memory search, recovery scheduler, and automatic schema-v13 offline sync remain active. | Migration V45.1, graph/memory migrations V45-V47, source/dist boundary test, PostgreSQL offline graph suite. |
| RDO | Creation starts from an authorized existing worksite, fills the RDO identity, imports the previous RDO workforce, lets the foreman add/deselect workers and change the `apontador`, records executed services, and exports online or offline with fail-closed validation. | RDO context V48/V50, local repositories, creation tests, PostgreSQL RDO context suite, XLSX parity report and sample. |
| Finance and PDOR | Service prices are maintained in Finance as immutable effective versions. Revenue comes from executed RDO services and their applicable prices. PDOR exposes revenue projection and evidence without subjective cost or margin. Ontology edges link execution to generated revenue. | Catalog migrations V49/V51, revenue migrations V52-V54, 17-test PostgreSQL revenue suite, trace/evidence UI tests. |
| UI and offline operation | The develop-equivalent institutional dark/minimal design is applied to the shell and product tabs, uses the full workspace, has no fabricated operational fallback, and remains PWA/offline capable with automatic sync. | Current localhost browser inspection, visual-policy tests, lint/build, 95-entry PWA precache, canonical IndexedDB tests. |

## RDO XLSX

The user attachment, the versioned web template, and the server template have
the same SHA-256:

```text
2a97db997d939b738146bad7c39428e38e159a6160f23afdf3297500fb2b8f87
```

The online and offline exporters are semantically equivalent across values,
types, merged cells, populated segments, and print areas. The durable sample is
[RDO-offline-sample.xlsx](RDO-offline-sample.xlsx), and the complete comparison
is in [rdo-export-evidence.md](rdo-export-evidence.md).

## Final verification matrix

```text
Backend full suite                 923 discovered; 870 passed; 53 skipped
PostgreSQL 18 integration suite     53 passed; 0 failed; 0 skipped; V54
Frontend assertions                514 passed in final sandbox run
Frontend full browser run          516 passed earlier in the same delivery
ESLint                              PASS
TypeScript/Vite production build   PASS; 224 modules
PWA generation                     PASS; 95 precache entries
Retired StavIA source/dist check   PASS
Secret/key literal scanner         PASS
Production compose resolution      PASS
Git whitespace validation          PASS
```

The two frontend assertions not relaunched in the final isolated run are
real-browser geometry subprocesses for Memory and RDO creation. The sandbox
prevented Chrome from opening its DevTools port before any geometry assertion
ran. Their earlier browser-enabled run passed, and the final interactive browser
inspection confirmed that `127.0.0.1:5173` is served from
`.worktrees/cortex-3-delivery/apps/web` and displays the current dark interface.

## Runtime honesty

The local PostgreSQL environment does not contain a fabricated ALFA/bootstrap
identity. Therefore `/rdos` correctly redirects to the institutional login
instead of showing hardcoded worksite, workforce, RDO, or finance data. The
authenticated flows are covered by source, authorization tests, offline tests,
and PostgreSQL integrations; no fake user or fixture was inserted to manufacture
a logged-in screenshot.

Security evidence is recorded in
[cortex-3-deep-scan.md](../../security/cortex-3-deep-scan.md) and
[secret-audit.md](secret-audit.md).
