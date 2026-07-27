import { spawnSync } from "node:child_process";

import { describe, expect, it } from "vitest";

const validator = new URL(
  "../validate-docker-build-args.sh",
  import.meta.url,
);

function validate(environment: Record<string, string>) {
  return spawnSync("sh", [validator.pathname], {
    encoding: "utf8",
    env: {
      PATH: process.env.PATH ?? "/usr/bin:/bin",
      ...environment,
    },
  });
}

describe("Docker web release build arguments", () => {
  it("accepts the documented PostgreSQL mode and public-key fingerprint", () => {
    const result = validate({
      VITE_CORTEX_AUTH_MODE: "postgresql",
      VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256: "A".repeat(43),
    });

    expect(result.status, result.stderr).toBe(0);
  });

  it.each([
    {
      name: "missing auth mode",
      environment: {
        VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256: "A".repeat(43),
      },
      message: "VITE_CORTEX_AUTH_MODE",
    },
    {
      name: "unknown auth mode",
      environment: {
        VITE_CORTEX_AUTH_MODE: "cpf",
        VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256: "A".repeat(43),
      },
      message: "VITE_CORTEX_AUTH_MODE",
    },
    {
      name: "missing offline signing fingerprint",
      environment: {
        VITE_CORTEX_AUTH_MODE: "postgresql",
      },
      message: "VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256",
    },
    {
      name: "short offline signing fingerprint",
      environment: {
        VITE_CORTEX_AUTH_MODE: "postgresql",
        VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256: "not-a-fingerprint",
      },
      message: "VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256",
    },
    {
      name: "non-base64url offline signing fingerprint",
      environment: {
        VITE_CORTEX_AUTH_MODE: "postgresql",
        VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256:
          `${"A".repeat(42)}+`,
      },
      message: "VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256",
    },
  ])("rejects $name", ({ environment, message }) => {
    const result = validate(environment);

    expect(result.status).not.toBe(0);
    expect(result.stderr).toContain(message);
  });
});
