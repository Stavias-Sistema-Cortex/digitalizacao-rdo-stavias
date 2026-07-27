import {
  apiError,
  apiFetch,
  fetchFreshCpfOfflineGrant,
  freshAuthenticationFetch,
  readResponseBody,
  revokeRemoteSessionCookie,
} from "../../lib/api/apiClient";
import type { AuthProfile } from "./authSession";
import { parseAuthProfile } from "./authProfile";
import {
  parseSignedOfflineGrant,
} from "./offlineVault";
import type { SignedOfflineGrant } from "./offlineVault.types";

export { parseAuthProfile } from "./authProfile";

export async function loginWithCpf(cpf: string): Promise<AuthProfile> {
  const response = await freshAuthenticationFetch("/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ cpf }),
  });
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw responseError(body, response.status);
  }
  return parseAuthProfile(body);
}

export async function fetchSession(): Promise<AuthProfile | null> {
  const response = await apiFetch("/auth/session");
  const body = await readResponseBody(response);
  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw responseError(body, response.status);
  }
  return parseAuthProfile(body);
}

export async function fetchOfflineGrant(): Promise<SignedOfflineGrant> {
  return parseOfflineGrantResponse(await apiFetch("/auth/offline-grant", {
    method: "POST",
  }));
}

/**
 * A direct CPF sign-in keeps remote access isolated until this signed grant
 * proves the current cookie still belongs to the profile returned by login.
 */
export async function fetchOfflineGrantAfterFreshCpfLogin(): Promise<
  SignedOfflineGrant
> {
  return parseOfflineGrantResponse(await fetchFreshCpfOfflineGrant());
}

async function parseOfflineGrantResponse(
  response: Response,
): Promise<SignedOfflineGrant> {
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw responseError(body, response.status);
  }
  return parseSignedOfflineGrant(body);
}

export async function logoutOnline(): Promise<"revoked"> {
  const response = await revokeRemoteSessionCookie();
  const body = await readResponseBody(response);
  if (response.status === 204) {
    return "revoked";
  }
  throw responseError(body, response.status);
}

function responseError(body: unknown, status: number): Error {
  return apiError(body, status);
}
