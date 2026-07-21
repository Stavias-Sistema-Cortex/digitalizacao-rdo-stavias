export type CortexAuthMode = "legacy" | "postgresql";

export type CortexAuthEnvironment = {
  readonly DEV?: boolean;
  readonly PROD?: boolean;
  readonly VITE_CORTEX_AUTH_MODE?: string;
};

/**
 * PostgreSQL access must be deliberately selected in a production bundle.
 * Development keeps the established legacy login unless the new mode is
 * explicitly requested, so local maintenance workflows are not changed.
 */
export function resolveCortexAuthMode(
  environment: CortexAuthEnvironment = import.meta.env,
): CortexAuthMode {
  const configured = environment.VITE_CORTEX_AUTH_MODE?.trim();
  if (configured === "legacy" || configured === "postgresql") {
    return configured;
  }

  if (!environment.PROD) {
    return "legacy";
  }

  throw new Error(
    "VITE_CORTEX_AUTH_MODE deve ser definido como legacy ou postgresql em produção.",
  );
}
