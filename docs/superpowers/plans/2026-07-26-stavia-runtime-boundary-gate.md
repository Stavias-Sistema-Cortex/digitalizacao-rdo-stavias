# StavIA Runtime Boundary Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair the false-positive runtime-boundary failure caused by the official Academy database identifier `dbstavias_acad`, while retaining a fail-closed ban on assistant runtime code, routes, resources, and packaged classes.

**Architecture:** The gate keeps a small, immutable receipt per legitimate compatibility reference. A receipt is valid only when the file path, reference count, and all exact source fragments match. The global assistant matcher stays unchanged. The three new source receipts and one production-class bytecode receipt join the existing allowlist; duplicate-reference tests ensure the exception cannot grow silently.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Maven, Spring Boot test classpath.

## Global Constraints

- Do not alter `ASSISTANT_REFERENCE`, `APPROVED_REFERENCE`, discovery exclusions, archive roots, or the package/JAR scan.
- Do not add a global exemption for `dbstavias_acad`, `Academy`, `config`, or test sources.
- Do not add an exception for `target/test-classes`; the gate only scans production classes and packaged application classes there.
- Retain the existing source and bytecode checks. An extra token occurrence, a moved fragment, an unauthorized file, or an unauthorized JAR entry must still fail.
- Preserve every unrelated dirty-worktree change. Stage only files named by this plan when making a commit.

---

## Task 1: Reproduce the narrow false positive with a direct regression test

**Files:**

- Modify: `apps/api/src/test/java/com/projeto/cortex/architecture/StaviaRuntimeBoundaryTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/architecture/StaviaRuntimeBoundaryTest.java`

- [ ] **Step 1: Add the failing source-receipt regression.**

  Add this test near the existing compatibility-reference tests. It intentionally scans the current bytes of the exact three source files before they are allowlisted.

  ```java
  @Test
  void permitsOnlyTheDeclaredAcademyCompatibilitySourceReferences() throws IOException {
      List<String> violations = new ArrayList<>();
      for (Path relative : List.of(
              Path.of("apps/api/src/main/java/com/projeto/cortex/config/"
                      + "PostgresqlRuntimeReadinessGuard.java"),
              Path.of("apps/api/src/test/java/com/projeto/cortex/config/"
                      + "PostgresqlRuntimeReadinessGuardTest.java"),
              Path.of("apps/api/src/test/java/com/projeto/cortex/integracoes/"
                      + "AcademyJdbcRuntimeContractTest.java"))) {
          inspectSourceFile(
                  relative,
                  Files.readAllBytes(repositoryRoot().resolve(relative)),
                  violations
          );
      }

      assertThat(violations).isEmpty();
  }
  ```

- [ ] **Step 2: Run the new regression in its red state.**

  Run:

  ```bash
  cd apps/api && ./mvnw -Dtest=StaviaRuntimeBoundaryTest#permitsOnlyTheDeclaredAcademyCompatibilitySourceReferences test
  ```

  Expected: the assertion reports the three files as `assistant content`; no production implementation is changed yet.

- [ ] **Step 3: Record only the three exact source compatibility receipts.**

  In `SCOPED_COMPATIBILITY_REFERENCES`, append these `sourceReference` values. The test fragment for the readiness guard test deliberately has no leading period because the source calls the static matcher directly.

  ```java
  sourceReference(
          "apps/api/src/main/java/com/projeto/cortex/config/"
                  + "PostgresqlRuntimeReadinessGuard.java",
          "dbstavias_acad",
          "AND c.banco_origem = 'dbstavias_acad'"),
  sourceReference(
          "apps/api/src/test/java/com/projeto/cortex/config/"
                  + "PostgresqlRuntimeReadinessGuardTest.java",
          "dbstavias_acad",
          "contains(\"c.banco_origem = 'dbstavias_acad'\")"),
  sourceReference(
          "apps/api/src/test/java/com/projeto/cortex/integracoes/"
                  + "AcademyJdbcRuntimeContractTest.java",
          "dbstavias_acad",
          "\"jdbc:mysql://127.0.0.1:3306/dbstavias_acad\"")
  ```

- [ ] **Step 4: Run the direct regression in its green state.**

  Run the same command from Step 2.

  Expected: `BUILD SUCCESS`; altering any listed fragment or count would leave the token visible to the global gate.

## Task 2: Retain bytecode coverage and make the source receipts non-expandable

**Files:**

- Modify: `apps/api/src/test/java/com/projeto/cortex/architecture/StaviaRuntimeBoundaryTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/architecture/StaviaRuntimeBoundaryTest.java`

- [ ] **Step 1: Add the exact production-bytecode receipt.**

  Add this `compiledReference` beside the other `dbstavias_acad` production-class entries:

  ```java
  compiledReference(
          "target/classes/com/projeto/cortex/config/"
                  + "PostgresqlRuntimeReadinessGuard.class",
          "dbstavias_acad", 1, "PostgresqlRuntimeReadinessGuard.java")
  ```

  It permits exactly one token in the compiled guard and verifies the compiler retained the expected source-file context. No test class is added to the compiled allowlist.

- [ ] **Step 2: Add duplicate-occurrence fixtures for every new source receipt.**

  Extend the `@ValueSource` in `rejectsAdditionalCompatibilityReferenceInItsEstablishedFile` with these three values:

  ```java
  "dbstavias_acad|apps/api/src/main/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuard.java",
  "dbstavias_acad|apps/api/src/test/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuardTest.java",
  "dbstavias_acad|apps/api/src/test/java/com/projeto/cortex/integracoes/AcademyJdbcRuntimeContractTest.java",
  ```

  The existing test appends a second literal token, and must see one `assistant content` violation because the exact-count receipt becomes invalid.

- [ ] **Step 3: Run source, behavior, and bytecode checks.**

  Run:

  ```bash
  cd apps/api && ./mvnw -Dtest=StaviaRuntimeBoundaryTest,PostgresqlRuntimeReadinessGuardTest,AcademyJdbcRuntimeContractTest test
  ./mvnw package -DskipTests
  ./mvnw -Dtest=StaviaRuntimeBoundaryTest test
  ```

  Expected: all focused tests pass. The last run scans the freshly compiled class and packaged JAR, proving that the only new bytecode exception is the guarded Academy identifier.

- [ ] **Step 4: Check the diff and commit only the gate change.**

  Run:

  ```bash
  git diff --check
  git diff -- apps/api/src/test/java/com/projeto/cortex/architecture/StaviaRuntimeBoundaryTest.java
  git add apps/api/src/test/java/com/projeto/cortex/architecture/StaviaRuntimeBoundaryTest.java
  git commit -m "fix(security): scope Academy runtime boundary receipt"
  ```

  Expected: no whitespace errors, no unrelated file staged, and a commit limited to the strict boundary-gate receipts and regression fixtures.

## Acceptance Criteria

- `PostgresqlRuntimeReadinessGuard.java`, its test, and `AcademyJdbcRuntimeContractTest.java` are accepted only at their exact existing token-bearing fragments.
- The guard’s compiled production class is accepted only with one token and the expected source-file context.
- Any duplicate token in the established source files fails.
- A token in an unapproved source, compiled class, or packaged JAR still fails.
- The existing assistant matcher and compiled/package scan remain intact.
