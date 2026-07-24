import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

describe("App normal authentication policy", () => {
  it("renders the CPF plus passkey login for an unauthenticated PostgreSQL app", () => {
    const source = readFileSync(
      new URL("./App.tsx", import.meta.url),
      "utf8",
    );

    expect(source).toContain(
      'import { LoginPage } from "./features/auth/LoginPage"',
    );
    expect(source).not.toContain("EmailOtpAccessForm");
    expect(source).not.toContain("PostgresqlAccessPage");
    expect(source).toMatch(
      /if \(resolveCortexAuthMode\(\) === "postgresql"\) \{\s*return <LoginPage\s*\/>;\s*\}/,
    );
  });
});
