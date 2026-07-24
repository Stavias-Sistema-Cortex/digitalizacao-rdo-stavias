import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

import * as XLSX from "@e965/xlsx";
import { unzipSync } from "fflate";
import { describe, expect, it } from "vitest";

import { createEmptyRdo } from "../createEmptyRdo";
import {
  RDO_TEMPLATE_SHA256,
  RDO_WORKBOOK_FIELDS,
  mapRdoWorkbook,
  type RdoWorkbookSnapshot,
} from "./rdoWorkbookMapping";
import { exportRdoWorkbook } from "./exportRdoWorkbook";

const SERVER_TEMPLATE = new URL(
  "../../../../../api/src/main/resources/rdo/export/RDO-v1.xlsx",
  import.meta.url,
);

function snapshot(): RdoWorkbookSnapshot {
  return {
    obra: {
      id: "obra-42",
      nome: "=2+3",
      codigoContrato: "CW-007",
    },
    rdo: {
      ...createEmptyRdo(),
      id: "rdo-42",
      obraId: "obra-42",
      numeroRdo: "RDO-0042",
      dataRdo: "2026-07-22",
      rodovia: "BR-101",
      turno: "DIURNO",
      horaInicio: "07:30",
      horaFim: "17:15",
      condicaoManha: "BOM",
      condicaoTarde: "CHUVA",
      condicaoNoite: "NUBLADO",
      pluviometriaMm: 12.5,
      kmInicialProgramado: "10+000",
      kmFinalProgramado: "11+000",
      kmInicialInterditado: "10+100",
      kmFinalInterditado: "10+900",
      previousRdoId: "rdo-41",
      observacoes: "Trecho liberado pela fiscalização.",
      apontadorRdo: "Ana Apontadora",
      encarregadoObra: "Enzo Encarregado",
      fiscalizacaoCampo: "Flávia Fiscal",
      maoObra: [
        {
          localId: "worker-1",
          origemItemId: "origin-1",
          sourceRdoId: "rdo-41",
          origin: "PREVIOUS_RDO",
          availability: "AVAILABLE",
          selected: true,
          colaboradorId: "col-1",
          nomeColaborador: "Ana Apontadora",
          cargo: "Apontador",
          tipoVinculo: "PROPRIO",
          quantidade: 1,
          horaInicio: "07:30",
          horaFim: "17:15",
          observacoes: "",
        },
        {
          localId: "worker-2",
          origemItemId: "",
          sourceRdoId: "",
          origin: "MANUAL",
          availability: "AVAILABLE",
          selected: true,
          colaboradorId: "col-2",
          nomeColaborador: "Olívia Operadora",
          cargo: "Operador",
          tipoVinculo: "TERCEIRIZADO",
          quantidade: 2,
          horaInicio: "07:30",
          horaFim: "17:15",
          observacoes: "",
        },
      ],
      equipamentos: [
        {
          localId: "equipment-1",
          assetId: "asset-1",
          prefixo: "EQ-7",
          descricao: "Escavadeira",
          tipoEquipamento: "ESCAVADEIRA",
          tipoVinculo: "PROPRIO",
          quantidade: 1,
          horaInicio: "07:30",
          horaFim: "17:15",
          observacoes: "",
        },
      ],
      controlesGeometricos: [
        {
          localId: "geometry-1",
          subtrecho: "km 10 ao km 11",
          numero: "01",
          estacaInicial: "10+000",
          estacaFinal: "11+000",
          kmInicial: "10",
          kmFinal: "11",
          pista: "Direita",
          faixa: "1",
          ordemServico: "OS-17",
          atividadeObservacoes: "Regularização do subleito",
          comprimentoM: 1_000,
          larguraM: 7.2,
          espessura1Cm: 4,
          espessura2Cm: 5,
          espessura3Cm: 6,
          densidade: 12.75,
          observacoes: "",
        },
      ],
      servicosExecutados: [
        {
          localId: "service-1",
          servicoNome: "Fresagem",
          itemContratualId: "item-1",
          quantidadeExecutada: 125.5,
          unidade: "m²",
          trechoInicial: "11+000",
          trechoFinal: "11+250",
          localizacao: "Pista direita",
          turno: "DIURNO",
          statusValidacao: "VALIDADA",
          retrabalho: false,
          producaoRejeitada: false,
          observacoes: "",
        },
      ],
      materiais: [
        {
          localId: "material-1",
          materialNome: "CAP",
          unidade: "t",
          quantidadePrevista: "",
          quantidadeUsinada: 15.5,
          quantidadeAplicada: 14.25,
          quantidadeSobra: "",
          notaFiscal: "NF-42",
          fornecedor: "Fornecedor",
          observacoes: "",
        },
      ],
    },
  };
}

function paritySnapshot(): RdoWorkbookSnapshot {
  return {
    obra: {
      id: "obra-7",
      nome: "Obra Norte",
      codigoContrato: "CW-007",
    },
    rdo: {
      ...createEmptyRdo(),
      id: "rdo-parity",
      obraId: "obra-7",
      numeroRdo: "RDO-PARITY",
      dataRdo: "2026-07-22",
      previousRdoId: "rdo-41",
      rodovia: "BR-101",
      kmInicialProgramado: "10+000",
      kmFinalProgramado: "11+000",
      kmInicialInterditado: "10+200",
      kmFinalInterditado: "10+800",
      turno: "DIURNO",
      horaInicio: "07:30",
      horaFim: "17:15",
      condicaoManha: "BOM",
      condicaoTarde: "CHUVA",
      condicaoNoite: "IMPOSSIBILITADO",
      pluviometriaMm: 3.25,
      observacoes:
        "@cmd ana@example.com 123.456.789-09 Bearer BEARER_SECRET_CANARY",
      apontadorRdo: "Ana Apontadora",
      encarregadoObra: "Enzo Encarregado",
      fiscalizacaoCampo: "Fiscal de Campo",
      maoObra: [{
        localId: "mo-1",
        origemItemId: "mo-anterior",
        sourceRdoId: "rdo-41",
        origin: "PREVIOUS_RDO",
        availability: "AVAILABLE",
        selected: true,
        colaboradorId: "col-ana",
        nomeColaborador: "Ana Apontadora",
        cargo: "Apontador",
        tipoVinculo: "CONTRATADO",
        quantidade: 1,
        horaInicio: "07:30",
        horaFim: "17:15",
        observacoes: "",
      }, {
        localId: "mo-2",
        origemItemId: "",
        sourceRdoId: "",
        origin: "MANUAL",
        availability: "AVAILABLE",
        selected: true,
        colaboradorId: "col-op",
        nomeColaborador: "Otávio Operador",
        cargo: "Operador",
        tipoVinculo: "SUBCONTRATADO",
        quantidade: 2,
        horaInicio: "08:00",
        horaFim: "16:00",
        observacoes: "",
      }],
      equipamentos: [{
        localId: "eq-1",
        assetId: "asset-7",
        prefixo: "EQ-7",
        descricao: "Escavadeira",
        tipoEquipamento: "ESCAVADEIRA",
        tipoVinculo: "PROPRIO",
        quantidade: 1,
        horaInicio: "08:00",
        horaFim: "16:00",
        observacoes: "Operação normal",
      }],
      materiais: [{
        localId: "mat-1",
        materialNome: "CAP",
        unidade: "t",
        quantidadePrevista: 16,
        quantidadeUsinada: 15.5,
        quantidadeAplicada: 14.25,
        quantidadeSobra: 1.25,
        notaFiscal: "NF-88",
        fornecedor: "Fornecedor",
        observacoes: "",
      }],
      controlesGeometricos: [{
        localId: "cg-parity",
        subtrecho: "km 10 ao km 11",
        numero: "1",
        estacaInicial: "10+000",
        estacaFinal: "11+000",
        kmInicial: "10",
        kmFinal: "11",
        pista: "Pista norte",
        faixa: "Direita",
        ordemServico: "OS-7",
        atividadeObservacoes: "Regularização",
        comprimentoM: 1_000,
        larguraM: 3.5,
        espessura1Cm: 4,
        espessura2Cm: 5,
        espessura3Cm: 6,
        densidade: 2.45,
        observacoes: "Controle aprovado",
      }],
      servicosExecutados: [{
        localId: "svc-1",
        servicoNome: "Fresagem",
        itemContratualId: "item-1",
        quantidadeExecutada: 125.5,
        unidade: "m²",
        trechoInicial: "10+000",
        trechoFinal: "10+500",
        localizacao: "Faixa direita",
        turno: "DIURNO",
        statusValidacao: "VALIDADA",
        retrabalho: false,
        producaoRejeitada: false,
        observacoes: "Sem intercorrências",
      }],
    },
  };
}

type PrintableBoundaryCase = {
  label: string;
  limit: number;
  mutate: (value: RdoWorkbookSnapshot, text: string) => void;
};

const JAVA_PRINTABLE_BOUNDARIES: PrintableBoundaryCase[] = [
  {
    label: "km inicial programado",
    limit: 12,
    mutate: (value, text) => { value.rdo.kmInicialProgramado = text; },
  },
  {
    label: "km final programado",
    limit: 12,
    mutate: (value, text) => { value.rdo.kmFinalProgramado = text; },
  },
  {
    label: "km inicial interditado",
    limit: 12,
    mutate: (value, text) => { value.rdo.kmInicialInterditado = text; },
  },
  {
    label: "km final interditado",
    limit: 12,
    mutate: (value, text) => { value.rdo.kmFinalInterditado = text; },
  },
  {
    label: "material",
    limit: 24,
    mutate: (value, text) => {
      value.rdo.materiais[0] = {
        ...value.rdo.materiais[0],
        materialNome: text,
        quantidadePrevista: "",
        quantidadeUsinada: "",
        quantidadeAplicada: "",
        quantidadeSobra: "",
      };
    },
  },
  {
    label: "número do trecho",
    limit: 12,
    mutate: (value, text) => { value.rdo.controlesGeometricos[0].numero = text; },
  },
  {
    label: "pista",
    limit: 16,
    mutate: (value, text) => { value.rdo.controlesGeometricos[0].pista = text; },
  },
  {
    label: "faixa",
    limit: 16,
    mutate: (value, text) => { value.rdo.controlesGeometricos[0].faixa = text; },
  },
  {
    label: "ordem de serviço",
    limit: 30,
    mutate: (value, text) => {
      value.rdo.controlesGeometricos[0].ordemServico = text;
    },
  },
];

async function templateBytes(): Promise<Uint8Array> {
  return new Uint8Array(await readFile(SERVER_TEMPLATE));
}

describe("RDO workbook mapping", () => {
  it("defines every semantic workbook field once", () => {
    expect(Object.keys(RDO_WORKBOOK_FIELDS)).toEqual([
      "header.obra",
      "header.contract",
      "header.rdoNumber",
      "header.date",
      "weather",
      "closure",
      "workforce.rows",
      "equipment.rows",
      "services.rows",
      "materials.rows",
      "geometricControl.rows",
      "observations",
      "signatures",
    ]);
  });

  it("keeps the bundled template pinned to the reviewed server hash", async () => {
    const digest = await crypto.subtle.digest("SHA-256", await templateBytes());
    const actual = Array.from(new Uint8Array(digest), (byte) =>
      byte.toString(16).padStart(2, "0"),
    ).join("");

    expect(actual).toBe(RDO_TEMPLATE_SHA256);
  });

  it("maps persisted values offline as literal cells on both sheets", async () => {
    const bytes = await exportRdoWorkbook(snapshot(), {
      templateBytes: await templateBytes(),
    });
    const workbook = XLSX.read(bytes, {
      type: "array",
      cellStyles: true,
    });
    const front = workbook.Sheets["v.1 RDO frente"];
    const back = workbook.Sheets["v.1 RDO verso"];

    expect(workbook.SheetNames).toEqual([
      "v.1 RDO frente",
      "v.1 RDO verso",
    ]);
    expect(front.B6).toMatchObject({ v: "'=2+3", t: "s" });
    expect(front.Q6).toMatchObject({ v: "CW-007", t: "s" });
    expect(front.B1).toMatchObject({
      v: "RELATÓRIO DIÁRIO DE OBRA (RDO) — RDO-0042",
      t: "s",
    });
    expect(front.AA6.t).toBe("n");
    expect(front.D10).toMatchObject({ v: "X", t: "s" });
    expect(front.G11).toMatchObject({ v: "X", t: "s" });
    expect(front.B16).toMatchObject({ v: "Apontador", t: "s" });
    expect(front.G16).toMatchObject({ v: 1, t: "n" });
    expect(front.M16).toMatchObject({ v: "Operador", t: "s" });
    expect(front.U16).toMatchObject({ v: 2, t: "n" });
    expect(front.B36).toMatchObject({ v: "Escavadeira", t: "s" });
    expect(front.O36).toMatchObject({ v: "EQ-7", t: "s" });
    expect(front.B60).toMatchObject({ v: "10+000", t: "s" });
    expect(front.X61).toMatchObject({
      v: "Fresagem | Quantidade: 125.5 m²",
      t: "s",
    });
    expect(back.B8).toMatchObject({ v: "CAP (U)", t: "s" });
    expect(back.E8).toMatchObject({ v: 15.5, t: "n" });
    expect(back.B26).toMatchObject({ v: "km 10 ao km 11", t: "s" });
    expect(back.L26).toMatchObject({ v: 0.04, t: "n" });
    expect(back.B69).toMatchObject({ v: "Ana Apontadora", t: "s" });
    expect(back.L69).toMatchObject({ v: "Enzo Encarregado", t: "s" });
    expect(back.B63.v).toContain("rdo-41");

    for (const sheetName of workbook.SheetNames) {
      const sheet = workbook.Sheets[sheetName];
      expect(Object.values(sheet).filter((cell) =>
        typeof cell === "object" && cell !== null && "f" in cell,
      )).toEqual([]);
    }
  }, 15_000);

  it("preserves merged printable regions and both print areas", async () => {
    const bytes = await exportRdoWorkbook(snapshot(), {
      templateBytes: await templateBytes(),
    });
    const workbook = XLSX.read(bytes, { type: "array", cellStyles: true });
    const frontMerges = workbook.Sheets["v.1 RDO frente"]["!merges"] ?? [];
    const backMerges = workbook.Sheets["v.1 RDO verso"]["!merges"] ?? [];

    expect(frontMerges.map(XLSX.utils.encode_range)).toContain("X61:AJ61");
    expect(backMerges.map(XLSX.utils.encode_range)).toContain("B63:AD68");
    expect(workbook.Workbook?.Names).toEqual([
      expect.objectContaining({
        Name: "_xlnm.Print_Area",
        Sheet: 0,
        Ref: "'v.1 RDO frente'!$A$1:$AJ$80",
      }),
      expect.objectContaining({
        Name: "_xlnm.Print_Area",
        Sheet: 1,
        Ref: "'v.1 RDO verso'!$A$2:$AH$70",
      }),
    ]);
  });

  it("preserves template borders and title alignment while clearing fixture data", async () => {
    const original = unzipSync(await templateBytes());
    const bytes = await exportRdoWorkbook(paritySnapshot(), {
      templateBytes: await templateBytes(),
    });
    const exported = unzipSync(bytes);
    const decode = (value: Uint8Array) => new TextDecoder().decode(value);
    const styleId = (sheetXml: string, address: string) =>
      new RegExp(`<c\\b(?=[^>]*\\br="${address}")[^>]*\\bs="(\\d+)"`)
        .exec(sheetXml)?.[1];
    const templateFront = decode(original["xl/worksheets/sheet1.xml"]);
    const exportedFront = decode(exported["xl/worksheets/sheet1.xml"]);
    const templateBack = decode(original["xl/worksheets/sheet2.xml"]);
    const exportedBack = decode(exported["xl/worksheets/sheet2.xml"]);

    for (const address of ["C17", "C37", "C62"]) {
      expect(styleId(exportedFront, address)).toBe(styleId(templateFront, address));
    }
    for (const address of ["C9", "C27"]) {
      expect(styleId(exportedBack, address)).toBe(styleId(templateBack, address));
    }

    const titleStyle = Number(styleId(exportedFront, "B1"));
    const cellStyles = /<cellXfs\b[^>]*>([\s\S]*?)<\/cellXfs>/
      .exec(decode(exported["xl/styles.xml"]))?.[1]
      .match(/<xf\b(?:[^>]*?\/>|[^>]*>[\s\S]*?<\/xf>)/g) ?? [];
    expect(cellStyles[titleStyle]).toContain('horizontal="center"');
    expect(cellStyles[titleStyle]).toContain('vertical="center"');
    expect(cellStyles[titleStyle]).toContain('shrinkToFit="1"');
  });

  it.each(JAVA_PRINTABLE_BOUNDARIES)(
    "matches Java fail-closed boundaries for $label",
    ({ label, limit, mutate }) => {
      for (const accepted of ["W".repeat(limit), "😀".repeat(limit)]) {
        const candidate = snapshot();
        mutate(candidate, accepted);
        expect(() => mapRdoWorkbook(candidate)).not.toThrow();
      }

      const message =
        `O conteúdo de ${label} não permanece legível no RDO (` +
        `limite de ${limit} caracteres em uma linha); ` +
        "nenhum conteúdo foi truncado.";
      for (const rejected of [
        "W".repeat(limit + 1),
        "valor\nquebra",
        "valor\rquebra",
      ]) {
        const candidate = snapshot();
        mutate(candidate, rejected);
        expect(() => mapRdoWorkbook(candidate)).toThrowError(
          expect.objectContaining({
            code: "RDO_EXPORT_PRINT_OVERFLOW",
            message,
          }),
        );
      }
    },
  );

  it("refuses an incomplete local snapshot with an exact code", async () => {
    await expect(
      exportRdoWorkbook({ ...snapshot(), obra: undefined }, {
        templateBytes: await templateBytes(),
      }),
    ).rejects.toMatchObject({ code: "RDO_EXPORT_MISSING_OBRA" });
  });

  it("fails closed instead of truncating rows", async () => {
    const overflowing = snapshot();
    overflowing.rdo.equipamentos = Array.from({ length: 33 }, (_, index) => ({
      ...overflowing.rdo.equipamentos[0],
      localId: `equipment-${index}`,
    }));

    await expect(exportRdoWorkbook(overflowing, {
      templateBytes: await templateBytes(),
    })).rejects.toMatchObject({ code: "RDO_EXPORT_OVERFLOW_EQUIPMENT" });
  });

  it.each([
    {
      section: "workforce",
      mutate(value: RdoWorkbookSnapshot) {
        value.rdo.maoObra[0].quantidade = "";
      },
      code: "RDO_EXPORT_INVALID_WORKFORCE_ROW",
    },
    {
      section: "equipment",
      mutate(value: RdoWorkbookSnapshot) {
        value.rdo.equipamentos[0].quantidade = "";
      },
      code: "RDO_EXPORT_INVALID_EQUIPMENT_ROW",
    },
    {
      section: "service",
      mutate(value: RdoWorkbookSnapshot) {
        value.rdo.servicosExecutados[0].quantidadeExecutada = "";
      },
      code: "RDO_EXPORT_INVALID_SERVICE_ROW",
    },
    {
      section: "material",
      mutate(value: RdoWorkbookSnapshot) {
        value.rdo.materiais[0].materialNome = "";
      },
      code: "RDO_EXPORT_INVALID_MATERIAL_ROW",
    },
  ])("rejects a partial $section row instead of inventing or dropping values", async ({ mutate, code }) => {
    const partial = snapshot();
    mutate(partial);

    await expect(exportRdoWorkbook(partial, {
      templateBytes: await templateBytes(),
    })).rejects.toMatchObject({ code });
  });

  it("redacts PII and secrets and neutralizes formula injection offline", async () => {
    const bytes = await exportRdoWorkbook(paritySnapshot(), {
      templateBytes: await templateBytes(),
    });
    const workbook = XLSX.read(bytes, { type: "array", cellStyles: true });
    const observations = workbook.Sheets["v.1 RDO verso"].B63.v as string;
    const packageText = Object.values(unzipSync(bytes))
      .map((entry) => new TextDecoder().decode(entry))
      .join("\n");

    expect(observations).toContain("[email removido]");
    expect(observations).toContain("[CPF removido]");
    expect(observations).toContain("Bearer [segredo removido]");
    expect(packageText).not.toContain("ana@example.com");
    expect(packageText).not.toContain("123.456.789-09");
    expect(packageText).not.toContain("BEARER_SECRET_CANARY");
    expect(packageText).not.toMatch(/<f(?:\s|>)/);
  });

  it("emits the Java parity fixture when an audit output path is requested", async () => {
    const output = process.env.CORTEX_RDO_OFFLINE_OUTPUT;
    if (!output) return;
    const bytes = await exportRdoWorkbook(paritySnapshot(), {
      templateBytes: await templateBytes(),
    });
    await mkdir(dirname(output), { recursive: true });
    await writeFile(output, bytes);
    expect(bytes.byteLength).toBeGreaterThan(0);
  });
});
