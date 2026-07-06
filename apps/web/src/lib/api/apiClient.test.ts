import { describe, expect, it } from "vitest";

import { responseErrorMessage } from "./apiClient";

describe("responseErrorMessage", () => {
  it("traduz bloqueio de CORS para uma mensagem operacional", () => {
    expect(responseErrorMessage("Invalid CORS request", 403)).toContain(
      "A API recusou a origem desta tela",
    );

    expect(
      responseErrorMessage({ message: "Invalid CORS request" }, 403),
    ).toContain("CORS");
  });
});
