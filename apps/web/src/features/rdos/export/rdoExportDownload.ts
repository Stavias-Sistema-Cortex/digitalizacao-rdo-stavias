export interface RdoExportDownloadOptions {
  beforeDownload?: () => void;
}

export function downloadRdoExportBlob(
  blob: Blob,
  filename: string,
  options: RdoExportDownloadOptions = {},
): void {
  options.beforeDownload?.();
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
