import { describe, expect, it } from "vitest";

import type {
  FinanceFiscalExtraction,
  FinanceSupplier,
} from "./financeiro.types";
import {
  documentTypeForExtraction,
  fiscalExtractionDefaults,
} from "./financeiroFiscal";

const EXTRACTION = {
  format: "XML",
  candidates: [
    { field: "numero", normalizedValue: "125" },
    { field: "fornecedorCnpj", normalizedValue: "12345678000195" },
    { field: "valorBruto", normalizedValue: 100 },
    { field: "desconto", normalizedValue: 5 },
    { field: "valorLiquido", normalizedValue: 95 },
  ],
} as FinanceFiscalExtraction;

describe("fiscal extraction form defaults", () => {
  it("preenche apenas candidatos reais e resolve fornecedor por CNPJ exato", () => {
    const suppliers = [{
      id: "supplier-1",
      cnpj: "12.345.678/0001-95",
    }] as FinanceSupplier[];

    expect(fiscalExtractionDefaults(EXTRACTION, suppliers)).toMatchObject({
      numero: "125",
      fornecedorId: "supplier-1",
      valorBruto: "100",
      desconto: "5",
      valorLiquido: "95",
    });
  });

  it("não associa fornecedor por CNPJ parcial ou ausente", () => {
    const suppliers = [{
      id: "supplier-1",
      cnpj: "12345678000100",
    }] as FinanceSupplier[];

    expect(fiscalExtractionDefaults(EXTRACTION, suppliers).fornecedorId)
      .toBeUndefined();
  });

  it("mapeia XML e representações visuais para tipos vinculáveis", () => {
    expect(documentTypeForExtraction(EXTRACTION)).toBe("XML");
    expect(documentTypeForExtraction({
      ...EXTRACTION,
      format: "PDF",
    })).toBe("DANFE");
  });
});
