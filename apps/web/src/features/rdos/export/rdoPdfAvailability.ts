import type { LocalRdoRecord, ObraLocalRecord } from "../../../lib/db/db.types";
import { buildRdoExportProjection } from "./rdoExportProjection";
import { validateOriginalPdfSources } from "./rdoPdfValidation";
import {
  localRdoExportAvailability,
  rdoWorkbookSnapshotFromLocalRecord,
  RdoWorkbookExportError,
  type RdoExportAvailability,
} from "./rdoWorkbookMapping";

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
