/**
 * @vitest-environment jsdom
 */
import { describe, expect, it, vi } from "vitest";

import {
  digitalizacaoEmFotos,
  fotoComoAnexoDoRascunho,
  type DocumentoRenderizavel,
} from "./rdoDigitalizadoEmFotos";

/*
 * O canvas não existe no ambiente de teste; o que se prende aqui é o contrato
 * com ele — quantas páginas viram foto, com que identidade, e que o arquivo
 * fica de fora do rascunho.
 */
function documento(paginas: number): DocumentoRenderizavel {
  const render = vi.fn(() => ({ promise: Promise.resolve() }));
  return {
    numPages: paginas,
    getPage: (numero: number) =>
      Promise.resolve({
        getViewport: ({ scale }: { scale: number }) => ({
          width: 1240 * scale,
          height: 1754 * scale,
        }),
        render: () => {
          void numero;
          return render();
        },
      }),
  };
}

function prepararCanvas(): void {
  const contexto = {} as CanvasRenderingContext2D;
  vi.spyOn(document, "createElement").mockImplementation(((
    tag: string,
  ) => {
    if (tag !== "canvas") {
      throw new Error(`elemento inesperado: ${tag}`);
    }
    return {
      width: 0,
      height: 0,
      getContext: () => contexto,
      toBlob: (
        retorno: (blob: Blob | null) => void,
        tipo: string,
      ) => {
        retorno(new Blob(["folha"], { type: tipo }));
      },
    } as unknown as HTMLCanvasElement;
  }) as typeof document.createElement);
}

describe("digitalização do RDO guardada como foto", () => {
  it("vira uma foto por página, identificada pela folha de origem", async () => {
    prepararCanvas();

    const fotos = await digitalizacaoEmFotos(
      documento(2),
      "rdo-99",
      "obra-9",
      "RDO 20-07.pdf",
    );

    expect(fotos).toHaveLength(2);
    expect(fotos[0]).toMatchObject({
      rdoId: "rdo-99",
      obraId: "obra-9",
      tipo: "FOTO",
      nome: "RDO 20-07 — página 1.jpg",
      nomeOriginal: "RDO 20-07.pdf",
      mimeType: "image/jpeg",
      syncStatus: "PENDING_SYNC",
    });
    expect(fotos[1].metadata).toMatchObject({
      origem: "RDO_DIGITALIZADO",
      pagina: 2,
    });
    vi.restoreAllMocks();
  });

  /*
   * O RDO de papel tem frente e verso. O corte segue o mesmo limite de fotos
   * que o RDO já tinha, para que um arquivo enorme não entre pela porta da
   * importação o que a porta da câmera recusa.
   */
  it("para no limite de fotos que o RDO já tinha", async () => {
    prepararCanvas();

    const fotos = await digitalizacaoEmFotos(
      documento(40),
      "rdo-99",
      null,
      "lote.pdf",
    );

    expect(fotos).toHaveLength(5);
    vi.restoreAllMocks();
  });

  it("deixa o arquivo fora do rascunho, que só carrega a ficha", async () => {
    prepararCanvas();

    const [foto] = await digitalizacaoEmFotos(
      documento(1),
      "rdo-99",
      null,
      "folha.pdf",
    );
    const anexo = fotoComoAnexoDoRascunho(foto);

    expect(anexo).not.toHaveProperty("arquivo");
    expect(anexo.id).toBe(foto.id);
    expect(anexo.nome).toBe(foto.nome);
    vi.restoreAllMocks();
  });
});
