// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  downloadRdoExportBlob,
  type RdoExportDownloadPermit,
} from "./rdoExportDownload";

const AUTHORIZATION_REQUIRED_MESSAGE =
  "A autorização atual para exportar o RDO não foi confirmada; o download foi bloqueado.";

function approvedPermit(): RdoExportDownloadPermit {
  return {
    assertCurrentAuthorization: () => true,
  };
}

describe("downloadRdoExportBlob", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("fails closed when authorization is omitted before allocating a blob URL or clicking a download", () => {
    const createObjectUrl = vi.spyOn(URL, "createObjectURL");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL");
    const createElement = vi.spyOn(document, "createElement");
    const withoutPermit = downloadRdoExportBlob as unknown as (
      blob: Blob,
      filename: string,
    ) => void;

    expect(() => withoutPermit(
      new Blob(["rdo"]),
      "rdo-RDO-0042.xlsx",
    )).toThrow(AUTHORIZATION_REQUIRED_MESSAGE);

    expect(createObjectUrl).not.toHaveBeenCalled();
    expect(revokeObjectUrl).not.toHaveBeenCalled();
    expect(createElement).not.toHaveBeenCalled();
  });

  it("checks a rejecting authorization before allocating a blob URL or clicking a download", () => {
    const createObjectUrl = vi.spyOn(URL, "createObjectURL");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL");
    const createElement = vi.spyOn(document, "createElement");

    expect(() => downloadRdoExportBlob(
      new Blob(["rdo"]),
      "rdo-RDO-0042.xlsx",
      {
        assertCurrentAuthorization: () => {
          throw new Error("A sessão mudou durante a exportação local do RDO; o download foi bloqueado.");
        },
      },
    )).toThrow("A sessão mudou durante a exportação local do RDO; o download foi bloqueado.");

    expect(createObjectUrl).not.toHaveBeenCalled();
    expect(revokeObjectUrl).not.toHaveBeenCalled();
    expect(createElement).not.toHaveBeenCalled();
  });

  it("fails closed and consumes an asynchronously rejected authorization before the blob sink", async () => {
    const createObjectUrl = vi.spyOn(URL, "createObjectURL");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL");
    const createElement = vi.spyOn(document, "createElement");
    const asynchronouslyRejectedPermit = {
      assertCurrentAuthorization: () => Promise.reject(
        new Error("A sessão mudou durante a exportação local do RDO; o download foi bloqueado."),
      ),
    } as unknown as RdoExportDownloadPermit;

    expect(() => downloadRdoExportBlob(
      new Blob(["rdo"]),
      "rdo-RDO-0042.xlsx",
      asynchronouslyRejectedPermit,
    )).toThrow(AUTHORIZATION_REQUIRED_MESSAGE);

    await Promise.resolve();
    await Promise.resolve();

    expect(createObjectUrl).not.toHaveBeenCalled();
    expect(revokeObjectUrl).not.toHaveBeenCalled();
    expect(createElement).not.toHaveBeenCalled();
  });

  it("downloads only after a synchronous approved permit", () => {
    const createObjectUrl = vi.spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:rdo-export");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => undefined);

    downloadRdoExportBlob(
      new Blob(["rdo"]),
      "rdo-RDO-0042.xlsx",
      approvedPermit(),
    );

    expect(createObjectUrl).toHaveBeenCalledOnce();
    expect(click).toHaveBeenCalledOnce();
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:rdo-export");
  });
});
