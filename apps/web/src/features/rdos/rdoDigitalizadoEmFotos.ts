import type {
  RdoAttachmentRecord,
} from "../../lib/db/db.types";
import type { RdoAttachmentDraft } from "./rdo.types";
import { RDO_IMPORT_LIMITS } from "../../lib/files/rdoImportResourcePolicy";

/**
 * A folha digitalizada guardada como as fotos do RDO.
 *
 * <p>O RDO fotografado não tem texto para ler — tem a imagem da folha. Recusar
 * o arquivo por isso deixava a pessoa sem saída: o papel existia, o RDO
 * precisava ser lançado, e o Córtex dizia não. Aqui a digitalização entra pelo
 * caminho que o RDO já tem para papel: cada página vira uma foto anexada, que
 * fica visível na própria tela enquanto se preenche.
 *
 * <p>Nada é lido automaticamente, de propósito. O reconhecimento de letra de
 * mão erra justamente onde não se pode errar — "01" vira "ol", "206+822" vira
 * "o6+BMA" — e um número errado com cara de lido é pior que um campo em
 * branco, porque ninguém confere o que parece pronto.
 */

const QUALIDADE_JPEG = 0.82;
/** A folha inteira precisa continuar legível na tela ao lado do formulário. */
const MAIOR_LADO_PX = 2000;

export interface PaginaRenderizavel {
  getViewport: (opcoes: { scale: number }) => {
    width: number;
    height: number;
  };
  render: (opcoes: {
    canvasContext: CanvasRenderingContext2D;
    canvas: HTMLCanvasElement;
    viewport: { width: number; height: number };
  }) => { promise: Promise<void> };
}

export interface DocumentoRenderizavel {
  numPages: number;
  getPage: (numero: number) => Promise<PaginaRenderizavel>;
}

async function paginaComoJpeg(pagina: PaginaRenderizavel): Promise<Blob> {
  const inicial = pagina.getViewport({ scale: 1 });
  const escala = Math.min(
    1,
    MAIOR_LADO_PX / Math.max(inicial.width, inicial.height),
  );
  const viewport = pagina.getViewport({ scale: escala });
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(viewport.width));
  canvas.height = Math.max(1, Math.round(viewport.height));
  const contexto = canvas.getContext("2d");
  if (!contexto) {
    throw new Error("Não foi possível preparar a imagem da folha digitalizada.");
  }
  // O PDF.js desta versão lê a própria tela junto do contexto; passar só o
  // contexto quebra dentro da biblioteca, longe de quem chamou.
  await pagina.render({ canvasContext: contexto, canvas, viewport }).promise;

  return await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (blob) resolve(blob);
        else reject(new Error("Não foi possível gerar a imagem da folha."));
      },
      "image/jpeg",
      QUALIDADE_JPEG,
    );
  });
}

/**
 * As páginas da digitalização, prontas para virar anexo do RDO.
 *
 * <p>O corte em cinco páginas é o mesmo limite de fotos que o RDO já tem: um
 * RDO de papel tem frente e verso, e um arquivo que traga muito mais que isso
 * não é um RDO.
 */
export async function digitalizacaoEmFotos(
  documento: DocumentoRenderizavel,
  rdoId: string,
  obraId: string | null,
  nomeArquivo: string,
): Promise<RdoAttachmentRecord[]> {
  const total = Math.min(
    documento.numPages,
    Math.min(5, RDO_IMPORT_LIMITS.pdfPages),
  );
  const fotos: RdoAttachmentRecord[] = [];

  for (let numero = 1; numero <= total; numero += 1) {
    const blob = await paginaComoJpeg(await documento.getPage(numero));
    const instante = new Date().toISOString();
    fotos.push({
      id: crypto.randomUUID(),
      rdoId,
      obraId,
      tipo: "FOTO",
      nome: `${nomeArquivo.replace(/\.[^.]+$/, "")} — página ${numero}.jpg`,
      nomeOriginal: nomeArquivo || null,
      mimeType: "image/jpeg",
      tamanhoBytes: blob.size,
      tamanhoOriginalBytes: blob.size,
      tamanhoComprimidoBytes: blob.size,
      arquivo: blob,
      syncStatus: "PENDING_SYNC",
      ultimoErro: null,
      metadata: {
        origem: "RDO_DIGITALIZADO",
        pagina: numero,
        compressionQuality: QUALIDADE_JPEG,
        compressionMaxEdgePx: MAIOR_LADO_PX,
      },
      createdAt: instante,
      updatedAt: instante,
      removedAt: null,
    });
  }

  return fotos;
}

/**
 * A foto guardada vista pelo rascunho, que não carrega o arquivo consigo.
 */
export function fotoComoAnexoDoRascunho(
  foto: RdoAttachmentRecord,
): RdoAttachmentDraft {
  return {
    id: foto.id,
    rdoId: foto.rdoId,
    obraId: foto.obraId,
    tipo: "FOTO",
    nome: foto.nome,
    nomeOriginal: foto.nomeOriginal,
    mimeType: foto.mimeType,
    tamanhoOriginalBytes: foto.tamanhoOriginalBytes,
    tamanhoComprimidoBytes: foto.tamanhoComprimidoBytes,
    tamanhoBytes: foto.tamanhoBytes,
    syncStatus: foto.syncStatus,
    createdAt: foto.createdAt,
    updatedAt: foto.updatedAt,
    removedAt: foto.removedAt,
    metadata: foto.metadata,
  };
}
