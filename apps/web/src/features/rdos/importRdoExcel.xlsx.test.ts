// @vitest-environment jsdom
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

import { importarRdoArquivo } from "./importRdoExcel";

describe("RDO XLSX import", () => {
  it("keeps the repository RDO-v1 template importable as an editable draft", async () => {
    const bytes = await readFile(
      resolve(
        process.cwd(),
        "src/features/rdos/export/RDO-v1.xlsx",
      ),
    );
    const file = new File([bytes], "RDO-v1.xlsx", {
      type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    });

    const imported = await importarRdoArquivo(
      file,
      "Apontador da sessão",
    );

    expect(imported.draft.numeroRdo).toBe("RDO-v1");
    expect(imported.draft.preenchidoPor).toBe("Apontador da sessão");
    expect(imported.summary).toContain("Importado de RDO-v1.xlsx");
  });
});
