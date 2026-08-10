// @vitest-environment jsdom
import { jsPDF } from "jspdf";
import { describe, expect, it, vi } from "vitest";

import { importarRdoArquivo } from "./importRdoExcel";

// Vitest resolves Vite's ?url module to an HTTP-style /node_modules path,
// while pdf.js's Node fake worker needs a file URL. The parser itself stays
// real; only the test-runtime worker URL is adapted.
vi.mock("pdfjs-dist/legacy/build/pdf.worker.mjs?url", () => ({
  default: `file://${process.cwd()}/node_modules/pdfjs-dist/legacy/build/pdf.worker.mjs`,
}));

/*
 * Desenhar a página exige o canvas de verdade, que o jsdom não tem. O
 * desenho tem teste próprio, contra um documento encenado; aqui o que se
 * verifica é a ligação — a folha sem texto vira anexo, o rascunho abre vazio
 * e nada sobe.
 */
vi.mock("./rdoDigitalizadoEmFotos", async (original) => {
  const real = await original<
    typeof import("./rdoDigitalizadoEmFotos")
  >();
  return {
    ...real,
    digitalizacaoEmFotos: vi.fn(
      async (
        _documento: unknown,
        rdoId: string,
        obraId: string | null,
        nomeArquivo: string,
      ) => [{
        id: "foto-1",
        rdoId,
        obraId,
        tipo: "FOTO" as const,
        nome: `${nomeArquivo} — página 1.jpg`,
        nomeOriginal: nomeArquivo,
        mimeType: "image/jpeg",
        tamanhoBytes: 8,
        tamanhoOriginalBytes: 8,
        tamanhoComprimidoBytes: 8,
        arquivo: new Blob(["folha"], { type: "image/jpeg" }),
        syncStatus: "PENDING_SYNC" as const,
        ultimoErro: null,
        metadata: { origem: "RDO_DIGITALIZADO", pagina: 1 },
        createdAt: "2026-08-10T00:00:00.000Z",
        updatedAt: "2026-08-10T00:00:00.000Z",
        removedAt: null,
      }],
    ),
  };
});

vi.mock("../../lib/db/rdoAttachmentRepository", () => ({
  putRdoAttachment: vi.fn(async () => undefined),
}));

function selectablePdfFile(lines: string[], name = "rdo-importado.pdf"): File {
  const document = new jsPDF({ unit: "pt", format: "a4" });
  lines.forEach((line, index) => {
    document.text(line, 40, 44 + (index * 18));
  });

  return new File([document.output("arraybuffer")], name, {
    type: "application/pdf",
  });
}

describe("RDO PDF import", () => {
  it("extracts a selectable RDO PDF into an editable draft without a worksheet fallback", async () => {
    const imported = await importarRdoArquivo(
      selectablePdfFile([
        "RELATÓRIO DIÁRIO DE OBRA",
        "Nº RDO: RDO-0042",
        "Cliente: Cliente Norte",
        "Nº DA OBRA: CTR-9",
        "Rodovia: BR-101",
        "15/07/2026",
        "Apontador RDO: Ana Apontadora",
        "Encarregado da Obra: Enzo Encarregado",
        "Fiscalização de Campo: Flávia Fiscal",
        "Turno: NOTURNO",
        "Hora Início: 07:30",
        "Hora Fim: 17:15",
      ]),
      "Sessão atual",
    );

    expect(imported.draft).toMatchObject({
      numeroRdo: "RDO-0042",
      dataRdo: "2026-07-15",
      cliente: "Cliente Norte",
      contrato: "CTR-9",
      rodovia: "BR-101",
      turno: "NOTURNO",
      horaInicio: "07:30",
      horaFim: "17:15",
      preenchidoPor: "Ana Apontadora",
      apontadorRdo: "Ana Apontadora",
      encarregadoObra: "Enzo Encarregado",
      fiscalizacaoCampo: "Flávia Fiscal",
    });
    expect(imported.summary).toContain("texto do PDF identificado");
    expect(imported.warnings).toEqual(expect.arrayContaining([
      expect.stringContaining("texto selecionável"),
      expect.stringContaining("CTR-9"),
    ]));
    expect(imported.draft.observacoes).toContain(
      "Texto extraído do PDF importado:",
    );
  });

  /*
   * A folha fotografada não tem texto, e recusá-la deixava a pessoa sem saída:
   * o papel existia e o RDO precisava ser lançado. Agora ela entra como as
   * fotos do RDO e o rascunho abre para preenchimento — vazio, porque nada foi
   * lido, e no aparelho, porque nada sobe sozinho.
   */
  it("abre a digitalização para preenchimento em vez de recusar o arquivo", async () => {
    const imported = await importarRdoArquivo(
      selectablePdfFile([], "rdo-escaneado.pdf"),
      "Sessão atual",
    );

    expect(imported.draft.attachments).not.toHaveLength(0);
    expect(imported.draft.attachments[0]).toMatchObject({
      tipo: "FOTO",
      mimeType: "image/jpeg",
    });
    expect(imported.warnings.join(" ")).toMatch(/nada foi lido automaticamente/i);
    expect(imported.warnings.join(" ")).toMatch(/nada foi enviado/i);
  });

  it("não inventa nenhum lançamento a partir da folha fotografada", async () => {
    const imported = await importarRdoArquivo(
      selectablePdfFile([], "rdo-escaneado.pdf"),
      "Sessão atual",
    );

    expect(imported.draft.maoObra).toEqual([]);
    expect(imported.draft.equipamentos).toEqual([]);
    expect(imported.draft.servicosExecutados).toEqual([]);
    expect(imported.draft.materiais).toEqual([]);
    expect(imported.draft.controlesGeometricos).toEqual([]);
    expect(imported.draft.syncStatus).toBe("LOCAL_ONLY");
  });

  it("returns a safe Portuguese error for a malformed PDF", async () => {
    const malformed = new File(
      ["%PDF-arquivo-corrompido"],
      "rdo-corrompido.pdf",
      {
        type: "application/pdf",
      },
    );

    await expect(
      importarRdoArquivo(malformed, "Sessão atual"),
    ).rejects.toThrow(
      "Não foi possível ler o PDF de RDO",
    );
  });
});
