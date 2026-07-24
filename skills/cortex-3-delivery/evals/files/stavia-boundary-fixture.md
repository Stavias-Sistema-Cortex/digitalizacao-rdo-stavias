# StavIA boundary fixture

Claimed patch:

- deleted `apps/web/src/features/stavia` and the launcher imports;
- deleted the entire backend `com.projeto.cortex.intelligence.stavia` package;
- kept the company logo files named `stavias-*`;
- `/api/ontology/entities` and `StaviaOntologyService` were inside the deleted package;
- `synchronizeOperationalData(obraId)` was called only by `StaviaReasoningService`;
- no new graph projector, checkpoint, archive, or PostgreSQL integration test exists;
- `mvn test -Dtest=RdoServiceTest` and the frontend build are green.

The reviewer must decide whether “StavIA removed” and “ontology/knowledge graph working perfectly” are proven.
