import type { DecimalValue } from "./servicePriceApi";

function decimalError(field: string): Error {
  return new Error(
    `O servidor retornou um valor decimal inválido em ${field}.`,
  );
}

export function exactScaledInteger(
  value: DecimalValue,
  scale: number,
  field: string,
): bigint {
  if (!Number.isInteger(scale) || scale < 0 || scale > 12) {
    throw new Error("Escala decimal inválida.");
  }

  const factor = 10 ** scale;
  if (typeof value === "number") {
    const scaled = value * factor;
    if (
      !Number.isFinite(value) ||
      value < 0 ||
      !Number.isSafeInteger(scaled)
    ) {
      throw decimalError(field);
    }
    return BigInt(scaled);
  }

  if (value !== value.trim()) {
    throw decimalError(field);
  }
  const match = /^(0|[1-9]\d*)(?:\.(\d+))?$/.exec(value);
  if (!match || (match[2]?.length ?? 0) > scale) {
    throw decimalError(field);
  }
  const fraction = (match[2] ?? "").padEnd(scale, "0");
  return BigInt(match[1]) * BigInt(factor) + BigInt(fraction || "0");
}

export function sumExactDecimals(
  values: readonly DecimalValue[],
  scale: number,
  field: string,
): bigint {
  return values.reduce(
    (sum, value) => sum + exactScaledInteger(value, scale, field),
    0n,
  );
}

export function formatScaledInteger(value: bigint, scale: number): string {
  const factor = 10n ** BigInt(scale);
  const integer = value / factor;
  const fraction = (value % factor).toString().padStart(scale, "0");
  const grouped = integer.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  return scale === 0 ? grouped : `${grouped},${fraction}`;
}

export function formatCurrencyDecimal(
  value: DecimalValue,
  digits = 2,
  field = "valor monetário",
): string {
  return `R$ ${formatScaledInteger(
    exactScaledInteger(value, digits, field),
    digits,
  )}`;
}

export function formatQuantityDecimal(
  value: DecimalValue,
  digits = 3,
  field = "quantidade",
): string {
  return formatScaledInteger(exactScaledInteger(value, digits, field), digits);
}
