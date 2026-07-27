import {
  RDO_IMPORT_LIMITS,
  RdoImportResourceError,
} from "../../lib/files/rdoImportResourcePolicy";

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
};

export async function extractBoundedPdfLines(
  document: PdfDocumentForTextExtraction,
  overrides: Partial<PdfTextLimits> = {},
): Promise<string[]> {
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

  const lines: string[] = [];
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
          items.push({ text, x, y });
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
