export interface FieldConflictValues {
  base?: unknown;
  local?: unknown;
  remote?: unknown;
}

export type FieldConflicts = Record<string, FieldConflictValues>;

export interface FieldConflictResolution {
  merged: Record<string, unknown>;
  conflicts: FieldConflicts;
  canAutoMerge: boolean;
}

export function classifyFieldConflict(
  baseValues: Record<string, unknown>,
  localValues: Record<string, unknown>,
  remoteValues: Record<string, unknown>,
): FieldConflictResolution {
  const merged: Record<string, unknown> = {};
  const conflicts: FieldConflicts = {};
  const fields = new Set([
    ...Object.keys(baseValues),
    ...Object.keys(localValues),
    ...Object.keys(remoteValues),
  ]);

  for (const field of [...fields].sort()) {
    const base = fieldValue(baseValues, field);
    const local = fieldValue(localValues, field);
    const remote = fieldValue(remoteValues, field);

    if (sameFieldValue(local, remote)) {
      copyPresentValue(merged, field, local);
      continue;
    }
    if (sameFieldValue(local, base)) {
      copyPresentValue(merged, field, remote);
      continue;
    }
    if (sameFieldValue(remote, base)) {
      copyPresentValue(merged, field, local);
      continue;
    }

    copyPresentValue(merged, field, local);
    setOwnDataProperty(
      conflicts,
      field,
      conflictValues(base, local, remote),
    );
  }

  return {
    canAutoMerge: Object.keys(conflicts).length === 0,
    merged,
    conflicts,
  };
}

interface PresentFieldValue {
  present: boolean;
  value: unknown;
  canonical: string | null;
}

function fieldValue(
  values: Record<string, unknown>,
  field: string,
): PresentFieldValue {
  const present = Object.prototype.hasOwnProperty.call(values, field);
  const value = values[field];

  return {
    present,
    value,
    canonical: present ? canonicalJson(value) : null,
  };
}

function sameFieldValue(
  left: PresentFieldValue,
  right: PresentFieldValue,
): boolean {
  return left.present === right.present && (
    !left.present || left.canonical === right.canonical
  );
}

function copyPresentValue(
  target: Record<string, unknown>,
  field: string,
  source: PresentFieldValue,
): void {
  if (source.present) {
    setOwnDataProperty(target, field, source.value);
  }
}

function setOwnDataProperty<T>(
  target: Record<string, T>,
  field: string,
  value: T,
): void {
  Object.defineProperty(target, field, {
    configurable: true,
    enumerable: true,
    value,
    writable: true,
  });
}

function conflictValues(
  base: PresentFieldValue,
  local: PresentFieldValue,
  remote: PresentFieldValue,
): FieldConflictValues {
  return {
    ...(base.present ? { base: base.value } : {}),
    ...(local.present ? { local: local.value } : {}),
    ...(remote.present ? { remote: remote.value } : {}),
  };
}

function canonicalJson(value: unknown): string {
  return canonicalJsonAt(value, "$", new WeakSet<object>());
}

function canonicalJsonAt(
  value: unknown,
  path: string,
  ancestors: WeakSet<object>,
): string {
  if (value === null) {
    return "null";
  }

  switch (typeof value) {
    case "string":
    case "boolean":
      return JSON.stringify(value);
    case "number":
      if (!Number.isFinite(value)) {
        throw new TypeError(
          `Canonical JSON rejects non-finite number at ${path}.`,
        );
      }
      return JSON.stringify(value);
    case "undefined":
      throw new TypeError(`Canonical JSON rejects undefined at ${path}.`);
    case "function":
      throw new TypeError(`Canonical JSON rejects functions at ${path}.`);
    case "bigint":
    case "symbol":
      throw new TypeError(
        `Canonical JSON rejects ${typeof value} at ${path}.`,
      );
    case "object":
      break;
  }

  if (ancestors.has(value)) {
    throw new TypeError(`Canonical JSON rejects cycles at ${path}.`);
  }
  ancestors.add(value);

  try {
    if (Array.isArray(value)) {
      const items: string[] = [];
      for (let index = 0; index < value.length; index += 1) {
        items.push(
          canonicalJsonAt(value[index], `${path}[${index}]`, ancestors),
        );
      }
      return `[${items.join(",")}]`;
    }

    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) {
      throw new TypeError(
        `Canonical JSON rejects non-plain object at ${path}.`,
      );
    }
    if (Object.getOwnPropertySymbols(value).length > 0) {
      throw new TypeError(
        `Canonical JSON rejects symbol keys at ${path}.`,
      );
    }

    const record = value as Record<string, unknown>;
    const entries = Object.keys(record).sort().map((key) =>
      `${JSON.stringify(key)}:${canonicalJsonAt(
        record[key],
        `${path}.${key}`,
        ancestors,
      )}`,
    );
    return `{${entries.join(",")}}`;
  } finally {
    ancestors.delete(value);
  }
}
