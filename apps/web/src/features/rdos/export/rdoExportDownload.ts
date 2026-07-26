export const RDO_EXPORT_DOWNLOAD_AUTHORIZATION_REQUIRED_MESSAGE =
  "A autorização atual para exportar o RDO não foi confirmada; o download foi bloqueado.";

export interface RdoExportDownloadPermit {
  readonly assertCurrentAuthorization: () => true;
}

export function assertRdoExportDownloadPermit(
  permit: RdoExportDownloadPermit,
): void {
  if (
    !permit ||
    typeof permit.assertCurrentAuthorization !== "function" ||
    permit.assertCurrentAuthorization() !== true
  ) {
    throw new Error(RDO_EXPORT_DOWNLOAD_AUTHORIZATION_REQUIRED_MESSAGE);
  }
}

export function downloadRdoExportBlob(
  blob: Blob,
  filename: string,
  permit: RdoExportDownloadPermit,
): void {
  assertRdoExportDownloadPermit(permit);
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.rel = "noopener";
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
