# StavIA backend archive

The StavIA assistant backend was archived from source commit `b9b619e` on
2026-07-21 as part of the Cortex 3 runtime boundary.

This directory is archive-only and non-build. It is outside every Maven source
and test root and must not be added to a runtime classpath, component scan, test
source set, generated source set, or packaged artifact. STAVIAS remains the
corporate/product brand; this archive is specifically the retired StavIA
assistant implementation.

## Graph capabilities extracted before archival

The reusable operational graph was reimplemented outside the assistant package
under `com.projeto.cortex.ontology.graph`:

- `GraphEntity`, `GraphRelation`, `GraphEvent`, `GraphState`, and
  `GraphEvidence`;
- `GraphProjectionBatch` and `CommittedOperationalEvent`;
- `OntologyGraphRepository`, `OperationalGraphProjector`,
  `GraphProjectionService`, and `PostgresqlOntologyGraphRepository`;
- `OntologyGraphQueryService` and `OntologyGraphController` for the independent
  `/api/ontology/**` API.

Assistant intents, prompts, response generation/formatting, query audit,
knowledge-source orchestration, and reprogramming concepts were not copied into
the graph runtime.

## Archive layout

- `backend/main/` contains the former
  `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/` tree.
- `backend/test/` contains its former package tests plus the assistant-only
  MySQL reader integration tests that previously imported that package.

## Restoration boundary

Restore StavIA only in a separate repository or a dedicated branch/worktree
created from `b9b619e`. Do not restore or compile it on a Cortex 3 branch. In
that isolated restoration line, use the archived package declarations and the
source-commit build/configuration as references, then explicitly re-establish
its dependencies, configuration, security controls, routes, and tests there.
Never merge that restoration into the Cortex 3 executable runtime.
