import { canonicalMutationJson } from "./mutationEnvelope";

export interface FieldValueEvidence {
  present: boolean;
  value?: unknown;
}

export interface FieldConflictEvidence {
  field: string;
  base: FieldValueEvidence;
  local: FieldValueEvidence;
  remote: FieldValueEvidence;
}

export interface RemoteSnapshotEvidence {
  complete: boolean;
  snapshot: Record<string, unknown> | null;
}

export interface FieldConflictResolution {
  merged: Record<string, unknown>;
  conflicts: FieldConflictEvidence[];
  canAutoMerge: boolean;
}

export function remoteSnapshotEvidence(
  conflict: Record<string, unknown> | null | undefined,
): RemoteSnapshotEvidence {
  if (!conflict || conflict.remoteCompleto !== true) {
    return { complete: false, snapshot: null };
  }
  const candidate =
    conflict.snapshotRemoto ?? conflict.estadoRemoto ?? conflict.remote;
  if (!isRecord(candidate)) {
    return { complete: false, snapshot: null };
  }
  // Clone and validate with the exact canonical serializer used by v13.
  return {
    complete: true,
    snapshot: JSON.parse(canonicalMutationJson(candidate)) as Record<
      string,
      unknown
    >,
  };
}

export function classifyFieldConflict(
  baseValues: Record<string, unknown>,
  localValues: Record<string, unknown>,
  remote: RemoteSnapshotEvidence,
): FieldConflictResolution {
  if (!remote.complete || !remote.snapshot) {
    const fields = changedFields(baseValues, localValues);
    return {
      merged: cloneRecord(localValues),
      conflicts: fields.map((field) => ({
        field,
        base: fieldEvidence(baseValues, field),
        local: fieldEvidence(localValues, field),
        remote: { present: false },
      })),
      canAutoMerge: false,
    };
  }

  const merged: Record<string, unknown> = {};
  const conflicts: FieldConflictEvidence[] = [];
  const fields = new Set([
    ...Object.keys(baseValues),
    ...Object.keys(localValues),
    ...Object.keys(remote.snapshot),
  ]);

  for (const field of [...fields].sort()) {
    const base = fieldEvidence(baseValues, field);
    const local = fieldEvidence(localValues, field);
    const remoteValue = fieldEvidence(remote.snapshot, field);

    if (sameValue(local, remoteValue)) {
      copyField(merged, field, local);
    } else if (sameValue(local, base)) {
      copyField(merged, field, remoteValue);
    } else if (sameValue(remoteValue, base)) {
      copyField(merged, field, local);
    } else {
      copyField(merged, field, local);
      conflicts.push({ field, base, local, remote: remoteValue });
    }
  }

  return {
    merged,
    conflicts,
    canAutoMerge: conflicts.length === 0,
  };
}

function changedFields(
  base: Record<string, unknown>,
  local: Record<string, unknown>,
): string[] {
  return [...new Set([...Object.keys(base), ...Object.keys(local)])]
    .filter(
      (field) =>
        !sameValue(
          fieldEvidence(base, field),
          fieldEvidence(local, field),
        ),
    )
    .sort();
}

function fieldEvidence(
  values: Record<string, unknown>,
  field: string,
): FieldValueEvidence {
  if (!Object.prototype.hasOwnProperty.call(values, field)) {
    return { present: false };
  }
  return { present: true, value: values[field] };
}

function sameValue(
  left: FieldValueEvidence,
  right: FieldValueEvidence,
): boolean {
  return (
    left.present === right.present &&
    (!left.present ||
      canonicalMutationJson(left.value) ===
        canonicalMutationJson(right.value))
  );
}

function copyField(
  target: Record<string, unknown>,
  field: string,
  evidence: FieldValueEvidence,
): void {
  if (evidence.present) {
    Object.defineProperty(target, field, {
      configurable: true,
      enumerable: true,
      value: evidence.value,
      writable: true,
    });
  }
}

function cloneRecord(
  value: Record<string, unknown>,
): Record<string, unknown> {
  return JSON.parse(canonicalMutationJson(value)) as Record<string, unknown>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return (
    value !== null &&
    typeof value === "object" &&
    !Array.isArray(value)
  );
}
