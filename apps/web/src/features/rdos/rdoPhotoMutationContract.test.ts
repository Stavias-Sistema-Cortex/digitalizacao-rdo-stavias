import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

const createPage = readFileSync(
  resolve(process.cwd(), "./src/features/rdos/RdoCreatePage.tsx"),
  "utf8",
);
const localRdoService = readFileSync(
  resolve(process.cwd(), "./src/lib/db/localRdoService.ts"),
  "utf8",
);

describe("RDO photo ontology contract", () => {
  it("does not create standalone legacy events while a photo is still an unsaved draft", () => {
    expect(createPage).not.toContain("addOperationalEvent");
  });

  it("adds photo lifecycle entries to the canonical RDO payload", () => {
    expect(localRdoService).toContain("buildPhotoLifecycleEvents");
    expect(localRdoService).toContain('type: "FOTO_ADICIONADA"');
    expect(localRdoService).toContain('type: "FOTO_REMOVIDA"');
  });
});
