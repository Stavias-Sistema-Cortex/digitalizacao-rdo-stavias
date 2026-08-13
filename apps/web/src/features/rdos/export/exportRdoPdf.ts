import { apiFetch, readResponseBody } from "../../../lib/api/apiClient";
import { responseErrorMessage } from "../../../lib/api/apiError";
import {
  assertRdoExportDownloadPermit,
  downloadRdoExportBlob,
  type RdoExportDownloadPermit,
} from "./rdoExportDownload";
import {
  rdoWorkbookFilename,
  type RdoExportIdentidade,
} from "./exportRdoWorkbook";
import { buildRdoPdf } from "./rdoPdfLayout";
import {
  type RdoWorkbookSnapshot,
} from "./rdoWorkbookMapping";

export { localRdoPdfExportAvailability } from "./rdoPdfAvailability";

export const RDO_PDF_MEDIA_TYPE = "application/pdf";

export function exportRdoPdf(snapshot: RdoWorkbookSnapshot): Uint8Array {
  return new Uint8Array(buildRdoPdf(snapshot).output("arraybuffer"));
}

export function rdoPdfFilename(snapshot: RdoExportIdentidade): string {
  return rdoWorkbookFilename(snapshot).replace(/\.xlsx$/, ".pdf");
}

export async function downloadRdoPdf(
  snapshot: RdoWorkbookSnapshot,
  permit: RdoExportDownloadPermit,
): Promise<void> {
  assertRdoExportDownloadPermit(permit);
  const bytes = exportRdoPdf(snapshot);
  const blob = new Blob([bytes.slice().buffer], {
    type: RDO_PDF_MEDIA_TYPE,
  });
  downloadRdoExportBlob(blob, rdoPdfFilename(snapshot), permit);
}

export async function downloadAuthoritativeRdoPdf(
  snapshot: RdoExportIdentidade,
  permit: RdoExportDownloadPermit,
): Promise<void> {
  assertRdoExportDownloadPermit(permit);
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
    // O servidor explica a recusa — campo fora do previsto, seção que não cabe
    // na folha, obra sem o que a capa exige. Trocar isso por um número deixava
    // quem clicou sem a única informação capaz de resolver o problema.
    throw new Error(
      responseErrorMessage(
        await readResponseBody(response),
        response.status,
      ),
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
    permit,
  );
}
