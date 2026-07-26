import type { LocalRdoRecord, ObraLocalRecord } from "../../../lib/db/db.types";
import type { RdoExportAvailability } from "./rdoWorkbookMapping";

export type LocalRdoPdfExportAvailability = (
  record: LocalRdoRecord,
  obra?: Pick<ObraLocalRecord, "id" | "nome" | "codigoContrato">,
) => RdoExportAvailability;

export async function loadLocalRdoPdfExportAvailability(): Promise<
  LocalRdoPdfExportAvailability
> {
  const { localRdoPdfExportAvailability } = await import("./exportRdoPdf");
  return localRdoPdfExportAvailability;
}
