import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

import webOntology from "./rdoOntology.json";

describe("rdoOntology parity", () => {
  it("cópia do web é idêntica ao recurso do backend", () => {
    const apiPath = fileURLToPath(
      new URL(
        "../../../../api/src/main/resources/stavia/rdo-ontology.json",
        import.meta.url,
      ),
    );

    const apiOntology = JSON.parse(readFileSync(apiPath, "utf-8"));

    expect(webOntology).toEqual(apiOntology);
  });
});
