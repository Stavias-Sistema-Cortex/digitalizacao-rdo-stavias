# StavIA boundary review

## Verdicts

| Requirement | Verdict | Evidence and boundary |
| --- | --- | --- |
| StavIA is removed | **Partially supported, not proven** | The claimed patch deletes the frontend feature, launcher imports, and the backend `com.projeto.cortex.intelligence.stavia` package. However, the fixture also says `stavias-*` logo files remain, and provides no repository-wide reference check, runtime boot check, or contract test. The implementation may be removed while stale product assets and references remain. |
| Ontology / knowledge graph is working perfectly | **Not proven; contradicted by the stated deletion** | `/api/ontology/entities` and `StaviaOntologyService` were deleted with the package. `synchronizeOperationalData(obraId)` had only one caller, `StaviaReasoningService`, which was also deleted. No replacement graph projector, checkpoint, archive, or PostgreSQL integration test exists. The two green commands establish only a narrow RDO unit-test target and a frontend compilation result; neither proves entity retrieval, graph persistence, operational-data ingestion, recovery, or PostgreSQL behavior. |

## Concrete risks

- Consumers of `/api/ontology/entities` now receive a missing endpoint or an incompatible replacement, if any.
- Operational RDO data can stop entering the ontology because its sole recorded trigger was removed.
- Without a replacement projector and checkpoint/archive, knowledge-graph state cannot be shown to be materialized, recoverable, or historically retained.
- The remaining `stavias-*` assets can preserve a misleading product surface or stale references after the feature's removal.
- Green `RdoServiceTest` and frontend build results cannot detect the missing cross-layer behavior above.

## Smallest verifiable correction sequence

1. Decide the intended contract: either remove ontology/knowledge-graph capability explicitly, or preserve it independently of StavIA.
2. If preserving it, move or replace `StaviaOntologyService`, `/api/ontology/entities`, and the `synchronizeOperationalData(obraId)` trigger with a non-StavIA owner; keep the public API contract explicit.
3. Add a PostgreSQL integration test that writes operational data, runs the replacement projection, and asserts persisted entity/relationship retrieval through the API.
4. Add a restart/recovery assertion for the required checkpoint or archive behavior, if historical graph continuity is a requirement.
5. Verify the removal boundary with repository-wide reference checks for StavIA launcher/package symbols and `stavias-*` assets; remove or deliberately retain each remaining asset with an owner and rationale.
6. Run the targeted integration test plus the existing RDO test and frontend build. Only then claim the selected contract is verified.
