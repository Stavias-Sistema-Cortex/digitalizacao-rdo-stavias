import type { RdoAttachmentRecord } from "../../lib/db/db.types";

const MAX_EDGE_PX = 1600;
const JPEG_QUALITY = 0.78;
const COMPRESSION_MIN_BYTES = 700_000;

export interface ProcessRdoPhotoInput {
  file: File;
  rdoId: string;
  obraId: string | null;
}

export interface ProcessRdoPhotoResult {
  attachment: RdoAttachmentRecord;
  wasCompressed: boolean;
}

export async function processRdoPhoto({
  file,
  rdoId,
  obraId,
}: ProcessRdoPhotoInput): Promise<ProcessRdoPhotoResult> {
  if (!file.type.startsWith("image/")) {
    throw new Error("Anexe apenas arquivos de imagem ao RDO.");
  }

  const timestamp = new Date().toISOString();
  const compressed = await maybeCompressImage(file);
  const blob = compressed.blob;
  const wasCompressed = blob.size < file.size;

  const attachment: RdoAttachmentRecord = {
    id: crypto.randomUUID(),
    rdoId,
    obraId,
    tipo: "FOTO",
    nome: file.name || `foto-rdo-${timestamp}.jpg`,
    nomeOriginal: file.name || null,
    mimeType: blob.type || file.type || "image/jpeg",
    tamanhoBytes: blob.size,
    tamanhoOriginalBytes: file.size,
    tamanhoComprimidoBytes: blob.size,
    arquivo: blob,
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    metadata: {
      largura: compressed.width,
      altura: compressed.height,
      compressionQuality: wasCompressed ? JPEG_QUALITY : null,
      compressionMaxEdgePx: wasCompressed ? MAX_EDGE_PX : null,
    },
    createdAt: timestamp,
    updatedAt: timestamp,
    removedAt: null,
  };

  return {
    attachment,
    wasCompressed,
  };
}

async function maybeCompressImage(file: File): Promise<{
  blob: Blob;
  width: number | null;
  height: number | null;
}> {
  if (file.size < COMPRESSION_MIN_BYTES) {
    const dimensions = await readImageDimensions(file);
    return {
      blob: file,
      width: dimensions.width,
      height: dimensions.height,
    };
  }

  const image = await loadImage(file);
  const scale = Math.min(
    1,
    MAX_EDGE_PX / Math.max(image.width, image.height),
  );
  const width = Math.max(1, Math.round(image.width * scale));
  const height = Math.max(1, Math.round(image.height * scale));

  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;

  const context = canvas.getContext("2d");
  if (!context) {
    return {
      blob: file,
      width: image.width,
      height: image.height,
    };
  }

  context.drawImage(image, 0, 0, width, height);

  const blob = await new Promise<Blob | null>((resolve) => {
    canvas.toBlob(resolve, "image/jpeg", JPEG_QUALITY);
  });

  if (!blob || blob.size >= file.size) {
    return {
      blob: file,
      width: image.width,
      height: image.height,
    };
  }

  return {
    blob,
    width,
    height,
  };
}

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();

    image.onload = () => {
      URL.revokeObjectURL(url);
      resolve(image);
    };

    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(
        new Error(
          "Não foi possível ler a foto para compressão local.",
        ),
      );
    };

    image.src = url;
  });
}

async function readImageDimensions(file: File): Promise<{
  width: number | null;
  height: number | null;
}> {
  try {
    const image = await loadImage(file);
    return {
      width: image.width,
      height: image.height,
    };
  } catch {
    return {
      width: null,
      height: null,
    };
  }
}
