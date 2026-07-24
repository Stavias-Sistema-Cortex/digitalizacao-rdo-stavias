export type CortexAuthMode = "legacy" | "postgresql";

export type CortexAuthEnvironment = {
  readonly DEV?: boolean;
  readonly PROD?: boolean;
  readonly VITE_CORTEX_AUTH_MODE?: string;
};

/** Cortex 3 defaults to PostgreSQL locally and requires an explicit release mode. */
export function resolveCortexAuthMode(
  environment: CortexAuthEnvironment = import.meta.env,
): CortexAuthMode {
  const configured = environment.VITE_CORTEX_AUTH_MODE?.trim();
  if (configured === "legacy" || configured === "postgresql") {
    return configured;
  }

  if (!environment.PROD) {
    return "postgresql";
  }

  throw new Error(
    "VITE_CORTEX_AUTH_MODE deve ser definido como legacy ou postgresql em produção.",
  );
}
