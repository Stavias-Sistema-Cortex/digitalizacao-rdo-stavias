// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";

import { downloadRdoExportBlob } from "./rdoExportDownload";

describe("downloadRdoExportBlob", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("checks authorization before allocating a blob URL or clicking a download", () => {
    const createObjectUrl = vi.spyOn(URL, "createObjectURL");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL");
    const createElement = vi.spyOn(document, "createElement");

    expect(() => downloadRdoExportBlob(
      new Blob(["rdo"]),
      "rdo-RDO-0042.xlsx",
      {
        beforeDownload: () => {
          throw new Error("A sessão mudou durante a exportação local do RDO; o download foi bloqueado.");
        },
      },
    )).toThrow("A sessão mudou durante a exportação local do RDO; o download foi bloqueado.");

    expect(createObjectUrl).not.toHaveBeenCalled();
    expect(revokeObjectUrl).not.toHaveBeenCalled();
    expect(createElement).not.toHaveBeenCalled();
  });
});
