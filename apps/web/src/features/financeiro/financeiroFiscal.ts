import type {
  FinanceFiscalExtraction,
  FinanceSupplier,
} from "./financeiro.types";

export type FiscalFormDefaults = Partial<Record<
  | "numero"
  | "serie"
  | "chaveAcesso"
  | "tipoDocumento"
  | "dataEmissao"
  | "dataCompetencia"
  | "vencimentoEm"
  | "moeda"
  | "valorBruto"
  | "desconto"
  | "acrescimo"
  | "retencoes"
  | "valorLiquido"
  | "fornecedorId",
  string
>>;

const FORM_FIELDS = new Set([
  "numero",
  "serie",
  "chaveAcesso",
  "tipoDocumento",
  "dataEmissao",
  "dataCompetencia",
  "vencimentoEm",
  "moeda",
  "valorBruto",
  "desconto",
  "acrescimo",
  "retencoes",
  "valorLiquido",
]);

export function fiscalExtractionDefaults(
  extraction: FinanceFiscalExtraction,
  suppliers: FinanceSupplier[],
): FiscalFormDefaults {
  const values: FiscalFormDefaults = {};
  for (const candidate of extraction.candidates) {
    if (!FORM_FIELDS.has(candidate.field)) continue;
    const value = primitive(candidate.normalizedValue);
    if (value !== undefined) {
      values[candidate.field as keyof FiscalFormDefaults] = value;
    }
  }

  const cnpj = extraction.candidates.find(
    (candidate) => candidate.field === "fornecedorCnpj",
  );
  const normalizedCnpj = digits(primitive(cnpj?.normalizedValue));
  if (normalizedCnpj) {
    const exactSupplier = suppliers.find(
      (supplier) => digits(supplier.cnpj) === normalizedCnpj,
    );
    if (exactSupplier) values.fornecedorId = exactSupplier.id;
  }
  return values;
}

export function documentTypeForExtraction(
  extraction: FinanceFiscalExtraction,
): "XML" | "DANFE" {
  return extraction.format === "XML" ? "XML" : "DANFE";
}

function primitive(value: unknown): string | undefined {
  if (typeof value === "string") return value;
  if (typeof value === "number" && Number.isFinite(value)) {
    return String(value);
  }
  if (typeof value === "boolean") return String(value);
  return undefined;
}

function digits(value: string | undefined): string {
  return value?.replace(/\D/g, "") ?? "";
}
