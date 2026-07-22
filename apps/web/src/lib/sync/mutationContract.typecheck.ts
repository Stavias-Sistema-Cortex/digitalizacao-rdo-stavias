import type { OperationalFeatureCollection } from "../../features/obras/map/mapGeometry";
import type {
  CanonicalOperationalEventInput,
  LegacyOperationalEventInput,
} from "../db/operationalEventRepository";
import type {
  CanonicalOperationalEventRecord,
  CanonicalOutboxMutationRecord,
  LegacyOperationalEventRecord,
  LegacyOutboxMutationRecord,
  MutationFieldPatch,
  MutationTrace,
  ObraGeometryLocalRecord,
  StoredOperationalEventRecord,
  StoredOutboxMutationRecord,
} from "../db/db.types";
import type { CommitLocalMutationInput } from "./localMutationCoordinator";

type Assert<T extends true> = T;

type Equal<Left, Right> =
  [Left] extends [Right]
    ? [Right] extends [Left]
      ? true
      : false
    : false;

type IsRequired<Shape, Key extends keyof Shape> =
  object extends Pick<Shape, Key> ? false : true;

type IsNotAssignable<Source, Target> =
  Source extends Target ? false : true;

/**
 * Compile-only assertions kept in the application TypeScript project so the
 * regular production build rejects a weakened canonical contract.
 */
export type CanonicalMutationContractTypecheck = [
  Assert<Equal<CanonicalOutboxMutationRecord["contractVersion"], 13>>,
  Assert<IsRequired<CanonicalOutboxMutationRecord, "fieldPatch">>,
  Assert<Equal<CanonicalOutboxMutationRecord["fieldPatch"], MutationFieldPatch>>,
  Assert<IsRequired<CanonicalOutboxMutationRecord, "trace">>,
  Assert<Equal<CanonicalOutboxMutationRecord["trace"], MutationTrace>>,
  Assert<IsRequired<CanonicalOutboxMutationRecord, "nextAttemptAt">>,
  Assert<IsRequired<CanonicalOutboxMutationRecord, "blockedReason">>,
  Assert<IsNotAssignable<LegacyOutboxMutationRecord, CanonicalOutboxMutationRecord>>,
  Assert<Equal<
    StoredOutboxMutationRecord,
    LegacyOutboxMutationRecord | CanonicalOutboxMutationRecord
  >>,
  Assert<Equal<CanonicalOperationalEventRecord["contractVersion"], 13>>,
  Assert<IsRequired<CanonicalOperationalEventRecord, "clientMutationId">>,
  Assert<IsRequired<CanonicalOperationalEventRecord, "deviceId">>,
  Assert<IsRequired<CanonicalOperationalEventRecord, "correlationId">>,
  Assert<IsRequired<CanonicalOperationalEventRecord, "causationId">>,
  Assert<IsRequired<CanonicalOperationalEventRecord, "previousState">>,
  Assert<IsRequired<CanonicalOperationalEventRecord, "newState">>,
  Assert<IsRequired<CanonicalOperationalEventRecord, "result">>,
  Assert<IsRequired<CanonicalOperationalEventRecord, "errorCategory">>,
  Assert<IsRequired<CanonicalOperationalEventRecord, "entityVersion">>,
  Assert<IsNotAssignable<LegacyOperationalEventRecord, CanonicalOperationalEventRecord>>,
  Assert<Equal<
    StoredOperationalEventRecord,
    LegacyOperationalEventRecord | CanonicalOperationalEventRecord
  >>,
  Assert<IsRequired<CanonicalOperationalEventInput, "clientMutationId">>,
  Assert<IsRequired<CanonicalOperationalEventInput, "deviceId">>,
  Assert<IsRequired<CanonicalOperationalEventInput, "correlationId">>,
  Assert<IsRequired<CanonicalOperationalEventInput, "causationId">>,
  Assert<IsRequired<CanonicalOperationalEventInput, "previousState">>,
  Assert<IsRequired<CanonicalOperationalEventInput, "newState">>,
  Assert<IsRequired<CanonicalOperationalEventInput, "result">>,
  Assert<IsRequired<CanonicalOperationalEventInput, "errorCategory">>,
  Assert<IsRequired<CanonicalOperationalEventInput, "entityVersion">>,
  Assert<IsNotAssignable<LegacyOperationalEventInput, CanonicalOperationalEventInput>>,
  Assert<Equal<ObraGeometryLocalRecord["geometry"], OperationalFeatureCollection>>,
  Assert<Equal<
    ReturnType<CommitLocalMutationInput<"rdos">["write"]>,
    undefined
  >>,
];
