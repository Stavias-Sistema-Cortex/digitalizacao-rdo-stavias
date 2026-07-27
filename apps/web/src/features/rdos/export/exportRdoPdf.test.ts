// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createEmptyRdo } from "../createEmptyRdo";
import {
  downloadAuthoritativeRdoPdf,
  downloadRdoPdf,
  exportRdoPdf,
  RDO_PDF_MEDIA_TYPE,
  rdoPdfFilename,
} from "./exportRdoPdf";
import { buildRdoPdf } from "./rdoPdfLayout";
import {
  RdoWorkbookExportError,
  type RdoExportErrorCode,
  type RdoWorkbookSnapshot,
} from "./rdoWorkbookMapping";
import type { RdoExportDownloadPermit } from "./rdoExportDownload";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
}));

vi.mock("../../../lib/api/apiClient", () => ({
  apiFetch: mocks.apiFetch,
}));

function snapshot(): RdoWorkbookSnapshot {
  return {
    obra: {
      id: "obra-42",
      nome: "Obra Norte",
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
      condicaoTarde: "NUBLADO",
      condicaoNoite: "CHUVA",
      pluviometriaMm: 3.25,
      kmInicialProgramado: "10+000",
      kmFinalProgramado: "11+000",
      kmInicialInterditado: "10+200",
      kmFinalInterditado: "10+800",
      observacoes: "Execução conferida.",
      apontadorRdo: "Ana Apontadora",
      encarregadoObra: "Enzo Encarregado",
      fiscalizacaoCampo: "Flávia Fiscal",
      maoObra: [{
        localId: "worker-1",
        origemItemId: "",
        sourceRdoId: "",
        origin: "MANUAL",
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
      }],
      equipamentos: [{
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
      }],
      materiais: [{
        localId: "material-1",
        materialNome: "CAP",
        unidade: "t",
        quantidadePrevista: "",
        quantidadeUsinada: 15.5,
        quantidadeAplicada: 14.25,
        quantidadeSobra: 1.25,
        notaFiscal: "NF-88",
        fornecedor: "Fornecedor",
        observacoes: "",
      }],
      controlesGeometricos: [{
        localId: "geometry-1",
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
        densidade: 12.75,
        observacoes: "",
      }],
      servicosExecutados: [{
        localId: "service-1",
        serviceId: "",
        priceVersionId: "",
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
      }],
    },
  };
}

const AUTHORIZATION_REQUIRED_MESSAGE =
  "A autorização atual para exportar o RDO não foi confirmada; o download foi bloqueado.";

function approvedPermit(): RdoExportDownloadPermit {
  return {
    assertCurrentAuthorization: () => true,
  };
}

function rejectingPermit(): RdoExportDownloadPermit {
  return {
    assertCurrentAuthorization: () => {
      throw new Error("A autorização do RDO foi revogada.");
    },
  };
}

const downloadLocalWithoutPermit = downloadRdoPdf as unknown as (
  value: RdoWorkbookSnapshot,
) => Promise<void>;
const downloadAuthoritativeWithoutPermit =
  downloadAuthoritativeRdoPdf as unknown as (
    value: RdoWorkbookSnapshot,
  ) => Promise<void>;

const directPdfDownloads = [
  {
    label: "local PDF",
    withoutPermit: () => downloadLocalWithoutPermit(snapshot()),
    withPermit: (permit: RdoExportDownloadPermit) =>
      downloadRdoPdf(snapshot(), permit),
  },
  {
    label: "authoritative PDF",
    withoutPermit: () => downloadAuthoritativeWithoutPermit(snapshot()),
    withPermit: (permit: RdoExportDownloadPermit) =>
      downloadAuthoritativeRdoPdf(snapshot(), permit),
  },
];

function pdfText(bytes: Uint8Array): string {
  return new TextDecoder("latin1").decode(bytes);
}

function expectPdfErrorWithoutOutput(
  value: RdoWorkbookSnapshot,
  code: RdoExportErrorCode,
  message?: string,
): void {
  let output: Uint8Array | undefined;
  let error: unknown;
  try {
    output = exportRdoPdf(value);
  } catch (candidate) {
    error = candidate;
  }

  expect(error).toBeInstanceOf(RdoWorkbookExportError);
  expect(error).toMatchObject({
    code,
    ...(message ? { message } : {}),
  });
  expect(output).toBeUndefined();
}

describe("local RDO PDF export", () => {
  beforeEach(() => {
    mocks.apiFetch.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("renders exactly two A4 faces and emits a PDF with a safe filename", () => {
    const value = snapshot();

    const document = buildRdoPdf(value);
    const bytes = exportRdoPdf(value);
    const content = pdfText(bytes);

    expect(document.getNumberOfPages()).toBe(2);
    expect(pdfText(bytes.slice(0, 5))).toBe("%PDF-");
    expect(content).toContain("/Subtype /Image");
    expect(content.match(/\/I\d+ Do/g)).toHaveLength(2);
    expect(content).not.toContain("STAVIAS");
    expect(bytes.byteLength).toBeLessThan(1_000_000);
    expect(content).toContain("MATERIAIS");
    expect(content).toContain("ASSINATURAS");
    expect(rdoPdfFilename(value)).toBe("rdo-RDO-0042.pdf");
  });

  it("embeds redacted observation text without the original secrets", () => {
    const value = snapshot();
    value.rdo.observacoes =
      "Contato ana@example.com, CPF 123.456.789-09, token=SECRET_CANARY";

    const content = pdfText(exportRdoPdf(value));

    expect(content).toContain("[email removido]");
    expect(content).toContain("[CPF removido]");
    expect(content).toContain("[segredo removido]");
    expect(content).not.toContain("ana@example.com");
    expect(content).not.toContain("123.456.789-09");
    expect(content).not.toContain("SECRET_CANARY");
  });

  it("redacts credential-bearing headers before emitting a local PDF", () => {
    const value = snapshot();
    value.rdo.observacoes = [
      "Authorization: Basic BASIC_SECRET_CANARY",
      "Proxy-Authorization: Basic PROXY_BASIC_SECRET_CANARY",
      "Authorization: Digest username=\"digest\", response=\"DIGEST_SECRET_CANARY\"",
      "Cookie: session=COOKIE_SECRET_CANARY",
      "Set-Cookie: session=SET_COOKIE_SECRET_CANARY",
    ].join("\n");

    const content = pdfText(exportRdoPdf(value));

    expect(content).toContain("[segredo removido]");
    expect(content).not.toContain("BASIC_SECRET_CANARY");
    expect(content).not.toContain("PROXY_BASIC_SECRET_CANARY");
    expect(content).not.toContain("DIGEST_SECRET_CANARY");
    expect(content).not.toContain("COOKIE_SECRET_CANARY");
    expect(content).not.toContain("SET_COOKIE_SECRET_CANARY");
  });

  it.each([
    ["folded Basic authorization", "Authorization: Basic\r\n BASIC_FOLDED_SECRET_CANARY", "BASIC_FOLDED_SECRET_CANARY"],
    ["folded Proxy Basic authorization", "Proxy-Authorization: Basic\r\n PROXY_BASIC_FOLDED_SECRET_CANARY", "PROXY_BASIC_FOLDED_SECRET_CANARY"],
    ["folded Digest authorization", "Authorization: Digest\r\n response=\"DIGEST_FOLDED_SECRET_CANARY\"", "DIGEST_FOLDED_SECRET_CANARY"],
    ["folded Cookie", "Cookie: session=one;\r\n COOKIE_FOLDED_SECRET_CANARY", "COOKIE_FOLDED_SECRET_CANARY"],
    ["folded Set-Cookie", "Set-Cookie: session=one;\r\n SET_COOKIE_FOLDED_SECRET_CANARY", "SET_COOKIE_FOLDED_SECRET_CANARY"],
  ])("redacts %s before emitting a local PDF", (_label, credentialHeader, canary) => {
    const value = snapshot();
    value.rdo.observacoes = credentialHeader;

    const content = pdfText(exportRdoPdf(value));

    expect(content).toContain("[segredo removido]");
    expect(content).not.toContain(canary);
  });

  it.each([
    ["NBSP Authorization", "Authorization\u00a0: Basic NBSP_AUTHORIZATION_SECRET_CANARY", "NBSP_AUTHORIZATION_SECRET_CANARY"],
    ["NBSP Cookie", "Cookie:\u00a0session=NBSP_COOKIE_SECRET_CANARY", "NBSP_COOKIE_SECRET_CANARY"],
  ])("redacts %s before emitting a local PDF", (_label, credentialHeader, canary) => {
    const value = snapshot();
    value.rdo.observacoes = credentialHeader;

    const content = pdfText(exportRdoPdf(value));

    expect(content).toContain("[segredo removido]");
    expect(content).not.toContain(canary);
  });

  it("preserves non-credential text after a bare Cookie label on a new line", () => {
    const value = snapshot();
    value.rdo.observacoes = "Cookie:\r\nTexto operacional preservado";

    const content = pdfText(exportRdoPdf(value));

    expect(content).toContain("Cookie:");
    expect(content).toContain("Texto operacional preservado");
    expect(content).not.toContain("[segredo removido]");
  });

  it("preserves the projection's missing-worksite and overflow failures", () => {
    const missingWorksite = snapshot();
    missingWorksite.obra = undefined;
    expect(() => buildRdoPdf(missingWorksite)).toThrowError(
      expect.objectContaining({ code: "RDO_EXPORT_MISSING_OBRA" }),
    );

    const overflowing = snapshot();
    overflowing.rdo.servicosExecutados = Array.from(
      { length: 22 },
      (_, index) => ({
        ...overflowing.rdo.servicosExecutados[0],
        localId: `service-${index}`,
        servicoNome: `Serviço ${index}`,
      }),
    );
    expect(() => buildRdoPdf(overflowing)).toThrowError(
      expect.objectContaining({ code: "RDO_EXPORT_OVERFLOW_SERVICES" }),
    );
  });

  it.each([
    ["an emoji", "Trecho liberado 😀"],
    ["a C0 control", "Trecho\u0001liberado"],
  ])("rejects %s before sanitization can substitute or drop it", (
    _label,
    observations,
  ) => {
    const value = snapshot();
    value.rdo.observacoes = observations;

    expectPdfErrorWithoutOutput(
      value,
      "RDO_EXPORT_UNSUPPORTED_PDF_GLYPH",
      "O conteúdo do RDO contém caractere sem representação segura no PDF; nenhum conteúdo foi substituído.",
    );
  });

  it.each([
    [
      "the apontador signature",
      (value: RdoWorkbookSnapshot) => {
        value.rdo.apontadorRdo = "\nAna Apontadora";
      },
    ],
    [
      "a workforce role",
      (value: RdoWorkbookSnapshot) => {
        value.rdo.maoObra[0].cargo = "\nApontador";
      },
    ],
    [
      "an equipment description",
      (value: RdoWorkbookSnapshot) => {
        value.rdo.equipamentos[0].descricao = "\nEscavadeira";
      },
    ],
    [
      "a material description",
      (value: RdoWorkbookSnapshot) => {
        value.rdo.materiais[0].materialNome = "\nCAP";
      },
    ],
    [
      "a geometric service order",
      (value: RdoWorkbookSnapshot) => {
        value.rdo.controlesGeometricos[0].ordemServico = "\nOS-7";
      },
    ],
    [
      "a geometric observation used as worked activity",
      (value: RdoWorkbookSnapshot) => {
        value.rdo.controlesGeometricos[0].atividadeObservacoes = "";
        value.rdo.controlesGeometricos[0].observacoes =
          "\nControle aprovado";
      },
    ],
    [
      "a worked service name",
      (value: RdoWorkbookSnapshot) => {
        value.rdo.servicosExecutados[0].servicoNome = "\nFresagem";
      },
    ],
  ])("rejects a transformed line break in %s before projection", (
    _label,
    mutate,
  ) => {
    const value = snapshot();
    mutate(value);

    expectPdfErrorWithoutOutput(
      value,
      "RDO_EXPORT_UNSUPPORTED_PDF_GLYPH",
      "O conteúdo do RDO contém caractere sem representação segura no PDF; nenhum conteúdo foi substituído.",
    );
  });

  it.each([
    [
      "service order",
      (value: RdoWorkbookSnapshot) => {
        value.rdo.controlesGeometricos[0].ordemServico = "W".repeat(30);
      },
    ],
    [
      "activity",
      (value: RdoWorkbookSnapshot) => {
        value.rdo.controlesGeometricos[0].atividadeObservacoes =
          "W".repeat(80);
      },
    ],
  ])("rejects unreadable minimum-size compression in the %s cell", (
    _label,
    mutate,
  ) => {
    const value = snapshot();
    mutate(value);

    expectPdfErrorWithoutOutput(
      value,
      "RDO_EXPORT_PRINT_OVERFLOW",
    );
  });

  it.each(directPdfDownloads)(
    "fails closed when authorization is omitted from the $label wrapper",
    async ({ withoutPermit }) => {
      mocks.apiFetch.mockResolvedValue(new Response("%PDF-authoritative", {
        status: 200,
        headers: { "Content-Type": "application/pdf" },
      }));
      const createObjectUrl = vi.spyOn(URL, "createObjectURL");
      const click = vi.spyOn(HTMLAnchorElement.prototype, "click");

      await expect(withoutPermit()).rejects.toThrow(
        AUTHORIZATION_REQUIRED_MESSAGE,
      );

      expect(mocks.apiFetch).not.toHaveBeenCalled();
      expect(createObjectUrl).not.toHaveBeenCalled();
      expect(click).not.toHaveBeenCalled();
    },
  );

  it.each(directPdfDownloads)(
    "fails closed when authorization rejects in the $label wrapper",
    async ({ withPermit }) => {
      mocks.apiFetch.mockResolvedValue(new Response("%PDF-authoritative", {
        status: 200,
        headers: { "Content-Type": "application/pdf" },
      }));
      const createObjectUrl = vi.spyOn(URL, "createObjectURL");
      const click = vi.spyOn(HTMLAnchorElement.prototype, "click");

      await expect(withPermit(rejectingPermit())).rejects.toThrow(
        "A autorização do RDO foi revogada.",
      );

      expect(mocks.apiFetch).not.toHaveBeenCalled();
      expect(createObjectUrl).not.toHaveBeenCalled();
      expect(click).not.toHaveBeenCalled();
    },
  );

  it("downloads local bytes only as application/pdf", async () => {
    let downloadedBlob: Blob | undefined;
    const createObjectUrl = vi.spyOn(URL, "createObjectURL")
      .mockImplementation((blob) => {
        downloadedBlob = blob;
        return "blob:rdo-pdf";
      });
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => undefined);

    await downloadRdoPdf(snapshot(), approvedPermit());

    expect(RDO_PDF_MEDIA_TYPE).toBe("application/pdf");
    expect(createObjectUrl).toHaveBeenCalledOnce();
    expect(downloadedBlob?.type).toBe(RDO_PDF_MEDIA_TYPE);
    expect(click).toHaveBeenCalledOnce();
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:rdo-pdf");
  });
});

describe("authoritative RDO PDF download", () => {
  beforeEach(() => {
    mocks.apiFetch.mockReset();
  });

  afterEach(() => {
    vi.doUnmock("./rdoPdfLayout");
    vi.resetModules();
    vi.restoreAllMocks();
  });

  it.each([
    [
      "a non-PDF response",
      new Response("<html>login</html>", {
        status: 200,
        headers: { "Content-Type": "text/html; charset=utf-8" },
      }),
      "sem um arquivo PDF válido",
    ],
    [
      "an HTTP 403",
      new Response("forbidden", {
        status: 403,
        headers: { "Content-Type": "application/pdf" },
      }),
      "recusou a exportação do RDO (403)",
    ],
  ])("rejects %s without invoking the local generator", async (
    _label,
    response,
    expectedMessage,
  ) => {
    const localGenerator = vi.fn(() => {
      throw new Error("local generator must not run");
    });
    vi.resetModules();
    vi.doMock("./rdoPdfLayout", () => ({
      buildRdoPdf: localGenerator,
    }));
    mocks.apiFetch.mockResolvedValue(response);
    const readBlob = vi.spyOn(response, "blob");
    const createObjectUrl = vi.spyOn(URL, "createObjectURL");
    const authoritative = await import("./exportRdoPdf");

    await expect(
      authoritative.downloadAuthoritativeRdoPdf(snapshot(), approvedPermit()),
    ).rejects.toThrow(expectedMessage);
    expect(localGenerator).not.toHaveBeenCalled();
    expect(readBlob).not.toHaveBeenCalled();
    expect(createObjectUrl).not.toHaveBeenCalled();
  });

  it.each(["connection unavailable", "request timed out"])(
    "propagates %s without invoking the local generator",
    async (message) => {
      const transportError = new Error(message);
      const localGenerator = vi.fn(() => {
        throw new Error("local generator must not run");
      });
      vi.resetModules();
      vi.doMock("./rdoPdfLayout", () => ({
        buildRdoPdf: localGenerator,
      }));
      mocks.apiFetch.mockRejectedValue(transportError);
      const authoritative = await import("./exportRdoPdf");

      await expect(
        authoritative.downloadAuthoritativeRdoPdf(snapshot(), approvedPermit()),
      ).rejects.toBe(transportError);
      expect(localGenerator).not.toHaveBeenCalled();
    },
  );

  it("uses the private PDF endpoint and the reviewed transport limits", async () => {
    mocks.apiFetch.mockResolvedValue(new Response("%PDF-authoritative", {
      status: 200,
      headers: { "Content-Type": "application/pdf; charset=binary" },
    }));
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:rdo-pdf");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => undefined);

    await downloadAuthoritativeRdoPdf(snapshot(), approvedPermit());

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/rdos/rdo-42/export.pdf",
      expect.objectContaining({
        method: "GET",
        timeoutMs: 30_000,
      }),
    );
  });

});
