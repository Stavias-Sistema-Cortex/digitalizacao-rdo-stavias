const DEFAULT_API_PREFIX = "/api";

/** Shared, dependency-free API URL validation for the pre-application root. */
export function apiUrl(path: string): string {
  const configuredBaseUrl =
    import.meta.env.VITE_CORTEX_API_BASE_URL?.trim();
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  if (!configuredBaseUrl) {
    return `${DEFAULT_API_PREFIX}${normalizedPath}`;
  }
  return `${validatedApiBaseUrl(configuredBaseUrl)}${normalizedPath}`;
}

function validatedApiBaseUrl(configuredBaseUrl: string): string {
  const isRootRelative =
    configuredBaseUrl.startsWith("/") &&
    !configuredBaseUrl.startsWith("//");
  const isAbsoluteHttp = /^https?:\/\//i.test(configuredBaseUrl);
  if (!isRootRelative && !isAbsoluteHttp) {
    throw new Error(
      "VITE_CORTEX_API_BASE_URL deve ser uma URL absoluta http(s) ou iniciar com /.",
    );
  }

  const browserOrigin =
    typeof window !== "undefined"
      ? window.location.origin
      : "http://localhost";
  let parsed: URL;
  try {
    parsed = new URL(configuredBaseUrl, browserOrigin);
  } catch {
    throw new Error(
      "VITE_CORTEX_API_BASE_URL não é uma URL válida.",
    );
  }

  if (
    parsed.username ||
    parsed.password ||
    parsed.search ||
    parsed.hash
  ) {
    throw new Error(
      "VITE_CORTEX_API_BASE_URL não pode conter credenciais, query ou fragmento.",
    );
  }

  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error(
      "VITE_CORTEX_API_BASE_URL deve usar HTTP ou HTTPS.",
    );
  }

  if (
    !isRootRelative &&
    typeof window !== "undefined" &&
    (parsed.hostname !== window.location.hostname ||
      parsed.protocol !== window.location.protocol)
  ) {
    throw new Error(
      "A API e a PWA devem usar o mesmo hostname e protocolo para que o cookie CSRF host-only funcione. Publique /api na mesma origem ou use apenas outra porta no desenvolvimento.",
    );
  }

  const normalizedPath =
    parsed.pathname === "/"
      ? ""
      : parsed.pathname.replace(/\/+$/, "");
  return isRootRelative
    ? normalizedPath
    : `${parsed.origin}${normalizedPath}`;
}
