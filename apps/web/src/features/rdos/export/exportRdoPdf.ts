import { apiFetch } from "../../../lib/api/apiClient";
import { downloadRdoExportBlob } from "./rdoExportDownload";
import { rdoWorkbookFilename } from "./exportRdoWorkbook";
import { buildRdoPdf, validateOriginalPdfSources } from "./rdoPdfLayout";
import { buildRdoExportProjection } from "./rdoExportProjection";
import {
  localRdoExportAvailability,
  rdoWorkbookSnapshotFromLocalRecord,
  RdoWorkbookExportError,
  type RdoExportAvailability,
  type RdoWorkbookSnapshot,
} from "./rdoWorkbookMapping";
import type { LocalRdoRecord, ObraLocalRecord } from "../../../lib/db/db.types";

export const RDO_PDF_MEDIA_TYPE = "application/pdf";

export function localRdoPdfExportAvailability(
  record: LocalRdoRecord,
  obra?: Pick<ObraLocalRecord, "id" | "nome" | "codigoContrato">,
): RdoExportAvailability {
  const availability = localRdoExportAvailability(record, obra);
  if (!availability.ready) {
    return availability;
  }

  try {
    const snapshot = rdoWorkbookSnapshotFromLocalRecord(record, obra);
    validateOriginalPdfSources(snapshot);
    buildRdoExportProjection(snapshot);
    return availability;
  } catch (caught) {
    if (caught instanceof RdoWorkbookExportError) {
      return {
        ready: false,
        code: caught.code,
        message: caught.message,
      };
    }
    throw caught;
  }
}

export function exportRdoPdf(snapshot: RdoWorkbookSnapshot): Uint8Array {
  return new Uint8Array(buildRdoPdf(snapshot).output("arraybuffer"));
}

export function rdoPdfFilename(snapshot: RdoWorkbookSnapshot): string {
  return rdoWorkbookFilename(snapshot).replace(/\.xlsx$/, ".pdf");
}

export async function downloadRdoPdf(
  snapshot: RdoWorkbookSnapshot,
): Promise<void> {
  const bytes = exportRdoPdf(snapshot);
  const blob = new Blob([bytes.slice().buffer], {
    type: RDO_PDF_MEDIA_TYPE,
  });
  downloadRdoExportBlob(blob, rdoPdfFilename(snapshot));
}

export async function downloadAuthoritativeRdoPdf(
  snapshot: RdoWorkbookSnapshot,
): Promise<void> {
  const response = await apiFetch(
    `/rdos/${encodeURIComponent(snapshot.rdo.id)}/export.pdf`,
    {
      method: "GET",
      timeoutMs: 30_000,
      connectionErrorMessage:
        "Não foi possível acessar a exportação autorizada do RDO no servidor.",
      timeoutErrorMessage:
        "A exportação autorizada do RDO excedeu o tempo limite.",
    },
  );
  if (!response.ok) {
    throw new Error(
      `O servidor recusou a exportação do RDO (${response.status}).`,
    );
  }
  const mediaType = response.headers
    .get("Content-Type")
    ?.split(";", 1)[0]
    .trim()
    .toLowerCase();
  if (mediaType !== RDO_PDF_MEDIA_TYPE) {
    throw new Error(
      "O servidor respondeu sem um arquivo PDF válido; o download foi bloqueado.",
    );
  }
  downloadRdoExportBlob(
    await response.blob(),
    rdoPdfFilename(snapshot),
  );
}
