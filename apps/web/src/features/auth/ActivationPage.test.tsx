import { readFileSync } from "node:fs";

import { describe, expect, it, vi } from "vitest";

import { ActivationPage } from "./ActivationPage";
import { probeActivationOnly } from "./activationBootstrap";

function jsonResponse(status: number, body: unknown): Response {
  return {
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

describe("activation-only startup", () => {
  it("identifica exclusivamente a resposta de ativação codificada", async () => {
    const activationFetch = vi.fn().mockResolvedValue(
      jsonResponse(503, { code: "CORTEX_ACTIVATION_ONLY" }),
    );
    await expect(probeActivationOnly(activationFetch)).resolves.toBe(true);

    const unrelatedFetch = vi.fn().mockResolvedValue(
      jsonResponse(503, { code: "UPSTREAM_UNAVAILABLE" }),
    );
    await expect(probeActivationOnly(unrelatedFetch)).resolves.toBe(false);
  });

  it("expõe uma raiz de ativação dedicada", () => {
    expect(ActivationPage).toBeTypeOf("function");
  });

  it("mantém e-mail e código fora do estado React", () => {
    const formSource = readFileSync(
      new URL("./EmailOtpAccessForm.tsx", import.meta.url),
      "utf8",
    );

    expect(formSource).not.toContain('const [email, setEmail]');
    expect(formSource).not.toContain('const [code, setCode]');
    expect(formSource).not.toContain("value={email}");
    expect(formSource).not.toContain("value={code}");
    expect(formSource).toContain("emailRef.current?.value");
    expect(formSource).toContain("codeRef.current?.value");
  });
});
