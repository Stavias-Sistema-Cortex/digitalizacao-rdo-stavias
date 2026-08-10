import {
  RDO_IMPORT_LIMITS,
  RdoImportResourceError,
} from "../../lib/files/rdoImportResourcePolicy";
import type { PaginaReconhecida } from "./rdoManuscrito";

type PdfTextContent = {
  items: unknown[];
};

type PdfTextStreamReader = {
  read: () => Promise<{
    done: boolean;
    value?: PdfTextContent;
  }>;
  cancel: (reason?: unknown) => Promise<void>;
  releaseLock: () => void;
};

type PdfPageForTextExtraction = {
  streamTextContent: () => {
    getReader: () => PdfTextStreamReader;
  };
};

export interface PdfDocumentForTextExtraction {
  numPages: number;
  getPage: (pageNumber: number) => Promise<PdfPageForTextExtraction>;
}

interface PdfTextLimits {
  pages: number;
  textItems: number;
  textChars: number;
}

type PdfTextLineItem = {
  text: string;
  x: number;
  y: number;
  width: number;
  height: number;
};

/**
 * As palavras de cada página, com a caixa de cada uma.
 *
 * <p>A extração em linhas nasceu aqui dentro e continua sendo feita a partir
 * desta coleta — uma só travessia do PDF, um só conjunto de limites. O que
 * mudou é que a posição de cada palavra deixa de ser jogada fora no caminho:
 * o formulário de RDO é uma tabela, e numa tabela a coluna em que o número
 * caiu é parte do que ele diz.
 */
async function collectBoundedPdfPages(
  document: PdfDocumentForTextExtraction,
  overrides: Partial<PdfTextLimits> = {},
): Promise<PdfTextLineItem[][]> {
  const limits: PdfTextLimits = {
    pages: overrides.pages ?? RDO_IMPORT_LIMITS.pdfPages,
    textItems:
      overrides.textItems ??
      RDO_IMPORT_LIMITS.pdfTextItems,
    textChars:
      overrides.textChars ??
      RDO_IMPORT_LIMITS.pdfTextChars,
  };

  if (
    !Number.isInteger(document.numPages) ||
    document.numPages < 1
  ) {
    throw new RdoImportResourceError(
      "O PDF de RDO não possui páginas válidas.",
    );
  }
  if (document.numPages > limits.pages) {
    throw new RdoImportResourceError(
      `O PDF de RDO possui mais de ${limits.pages} páginas.`,
    );
  }

  const pages: PdfTextLineItem[][] = [];
  let totalItems = 0;
  let totalChars = 0;

  for (
    let pageNumber = 1;
    pageNumber <= document.numPages;
    pageNumber += 1
  ) {
    const page = await document.getPage(pageNumber);
    const stream = page.streamTextContent();
    if (!stream || typeof stream.getReader !== "function") {
      throw new RdoImportResourceError(
        "O conteúdo de texto do PDF de RDO é inválido.",
      );
    }
    const reader = stream.getReader();
    const items: PdfTextLineItem[] = [];
    let streamComplete = false;
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) {
          streamComplete = true;
          break;
        }
        if (!chunk.value || !Array.isArray(chunk.value.items)) {
          throw new RdoImportResourceError(
            "O conteúdo de texto do PDF de RDO é inválido.",
          );
        }
        for (const item of chunk.value.items) {
          totalItems += 1;
          if (totalItems > limits.textItems) {
            throw new RdoImportResourceError(
              `O PDF de RDO possui mais de ${limits.textItems} itens de texto.`,
            );
          }

          const textItem = item as {
            str?: unknown;
            transform?: unknown;
            width?: unknown;
            height?: unknown;
          };
          if (typeof textItem.str !== "string") {
            continue;
          }
          totalChars += codePointLength(textItem.str);
          if (totalChars > limits.textChars) {
            throw new RdoImportResourceError(
              `O PDF de RDO possui mais de ${limits.textChars} caracteres de texto.`,
            );
          }

          const text = textItem.str.replace(/\s+/g, " ").trim();
          if (
            !text ||
            !Array.isArray(textItem.transform) ||
            textItem.transform.length < 6
          ) {
            continue;
          }
          const x = Number(textItem.transform[4]);
          const y = Number(textItem.transform[5]);
          if (!Number.isFinite(x) || !Number.isFinite(y)) {
            continue;
          }
          const width = Number(textItem.width);
          const height = Number(textItem.height);
          items.push({
            text,
            x,
            y,
            width: Number.isFinite(width) && width > 0 ? width : text.length * 5,
            height: Number.isFinite(height) && height > 0 ? height : 10,
          });
        }
      }
    } finally {
      if (!streamComplete) {
        try {
          await reader.cancel();
        } catch {
          // Preserve the bounded extraction error even if PDF.js cancellation
          // races with a worker that has already terminated.
        }
      }
      reader.releaseLock();
    }

    pages.push(items);
  }

  return pages;
}

export async function extractBoundedPdfLines(
  document: PdfDocumentForTextExtraction,
  overrides: Partial<PdfTextLimits> = {},
): Promise<string[]> {
  return linhasDasPaginas(
    await collectBoundedPdfPages(document, overrides),
  );
}

function linhasDasPaginas(pages: PdfTextLineItem[][]): string[] {
  const lines: string[] = [];

  for (const items of pages) {
    items.sort((left, right) => {
      const yDistance = right.y - left.y;
      return Math.abs(yDistance) > 2
        ? yDistance
        : left.x - right.x;
    });

    const pageGroups: PdfTextLineItem[][] = [];
    let currentGroup: PdfTextLineItem[] | null = null;
    for (const item of items) {
      if (
        currentGroup &&
        Math.abs(currentGroup[0].y - item.y) <= 2.5
      ) {
        currentGroup.push(item);
      } else {
        currentGroup = [item];
        pageGroups.push(currentGroup);
      }
    }
    for (const group of pageGroups) {
      lines.push(group
        .map((candidate) => candidate.text)
        .join(" ")
        .replace(/\s+/g, " ")
        .trim());
    }
  }

  return lines.filter(Boolean);
}

/**
 * As mesmas palavras, prontas para o encaixe no formulário de RDO.
 *
 * <p>O PDF conta o eixo vertical de baixo para cima; a folha, de cima para
 * baixo. A inversão acontece aqui, uma vez, para que quem lê o formulário
 * raciocine na ordem em que a página é lida.
 */
function paginasReconhecidas(
  pages: PdfTextLineItem[][],
): PaginaReconhecida[] {
  return pages.map((items) => {
    const palavras = items.map((item) => ({
      texto: item.text,
      x: item.x,
      y: -item.y,
      largura: item.width,
      altura: item.height,
    }));
    return {
      palavras,
      larguraPagina: palavras.reduce(
        (maior, palavra) => Math.max(maior, palavra.x + palavra.largura),
        0,
      ),
      alturaPagina: palavras.reduce(
        (maior, palavra) => Math.max(maior, palavra.y + palavra.altura),
        0,
      ),
    };
  });
}

/**
 * As linhas e as palavras posicionadas de uma só leitura do arquivo.
 *
 * <p>Pedir as duas coisas em chamadas separadas percorreria o PDF duas vezes
 * e contaria os limites de páginas, itens e caracteres em dobro — o mesmo
 * arquivo passaria a ser recusado por metade do tamanho que a política
 * permite.
 */
export async function extractBoundedPdfContent(
  document: PdfDocumentForTextExtraction,
  overrides: Partial<PdfTextLimits> = {},
): Promise<{ lines: string[]; paginas: PaginaReconhecida[] }> {
  const pages = await collectBoundedPdfPages(document, overrides);
  return {
    paginas: paginasReconhecidas(pages),
    lines: linhasDasPaginas(pages),
  };
}

function codePointLength(value: string): number {
  let length = 0;
  for (let index = 0; index < value.length; index += 1) {
    const codePoint = value.codePointAt(index);
    if (codePoint !== undefined && codePoint > 0xffff) {
      index += 1;
    }
    length += 1;
  }
  return length;
}
