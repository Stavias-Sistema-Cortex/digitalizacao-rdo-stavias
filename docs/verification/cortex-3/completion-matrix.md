# Cortex 3 current-revision completion matrix

Revision status: `VERIFIED INTEGRATION TREE`; authenticated local runtime and
remote-ref equality are tracked separately.

Use `PROVEN` only when the evidence was produced from the exact commit that will
be published. Source inspection is not browser/runtime proof, a unit test is not
PostgreSQL proof, and a prior V57/V58 result is not V59 proof.

| Requirement | Status | Evidence required for `PROVEN` |
| --- | --- | --- |
| StavIA launcher/assistant is absent from the active frontend and generated bundle while STAVIAS branding remains | PROVEN | Current source/build boundary gate and generated-bundle scan passed; compatibility backend controllers are outside this frontend removal claim |
| Ontology, knowledge graph, projection recovery, and Memory search work independently of StavIA | PROVEN | API 970-test suite and PostgreSQL V59 graph/search/recovery integrations passed |
| Offline writes, subject isolation, automatic reconnect, idempotent replay, and conflict state are truthful | PROVEN | Full web IndexedDB/sync suite and PostgreSQL replay/authorization integrations passed |
| A new RDO starts from an authorized obra and receives its canonical identity automatically | PROVEN IN COMPONENT/PG GATES; LIVE SESSION PENDING | Controller/service/UI tests and V48/V50/V55/V57 context-provenance integrations passed; no real local identity/data was invented for a screenshot |
| Previous eligible workforce is carried forward and workers/apontador remain editable | PROVEN IN COMPONENT/PG GATES; LIVE SESSION PENDING | PostgreSQL context/provenance and online/offline state tests passed; authenticated local browser session is unavailable |
| Online and offline RDO XLSX exports match the supplied model and fail closed | PROVEN | Current Java/TypeScript regeneration is semantically equivalent; all supplied/current sheets were rendered; template hashes, limits and active-content checks passed |
| The active Financeiro frontend exposes only `Rastreio de receita`, `Serviços e preços`, and `PDOR` | PROVEN | Route/navigation policy, 20 focused tests and generated chunks passed; no legacy finance panel/route is reachable from the active UI. This row does not claim removal of every legacy backend controller |
| Service price versions are persisted, immutable, scoped, and authoritative | PROVEN | Catalog/sync authorization tests and PostgreSQL V59 integration passed |
| Revenue is accepted RDO quantity multiplied by snapshotted service price, with exact decimal and canonical evidence | PROVEN | Calculator/serialization plus V58/V59 PostgreSQL integrity/trace integrations passed |
| PDOR is revenue-only, reproducible, cached per subject/scope/obra, records ontology provenance transactionally when published, and never presents a failed recalculation as current | PROVEN | API/web/IndexedDB and PostgreSQL snapshot/publication/failure integrations passed; V59 backfills historical revenue evidence, not a PDOR chain |
| CortexUI uses the black-to-green shell/header, full workspace, responsive scrolling, and real data/empty states | PROVEN BY BUILD/DOM/CSS; AUTHENTICATED PIXEL CAPTURE PENDING | 61 focused visual/finance tests, full web gate and `/financeiro` HTTP response passed; no Chromium screenshot is claimed |
| PostgreSQL `StaviasCortex` is the sole mutable server source and the release gate requires schema V59 | PROVEN | PostgreSQL-only runtime contracts and clean exact 17-version chain `44,45,45.1,46,47,48,49,50,51,52,53,54,55,56,57,58,59` passed |
| Frontend/backend authorization, keys, secrets, dependencies, exports, and local compose pass security review | PROVEN STATIC/TEST; CONTAINER START PENDING | Scanners, compose contract, npm audit, OWASP, negative authorization tests and final diff passed; Docker socket was unavailable |
| The remote `develop` revision matches the verified commit | PENDING | Verified commit SHA, push result, and `git ls-remote` equality after every gate above |

The local canonical database is at V59 with zero failed migrations, but it
contains `0` ALFA identities, `0` obras, and `0` RDOs, and no real
bootstrap/SMTP secrets are available. Those facts make authenticated browser
rows remain `PENDING`; they must not be converted to `PROVEN` by inserting fake
identity or operational data.
