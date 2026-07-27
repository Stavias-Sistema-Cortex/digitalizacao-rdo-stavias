import {
  apiError,
  apiFetch,
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
  const response = await apiFetch("/auth/offline-grant", {
    method: "POST",
  });
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw responseError(body, response.status);
  }
  return parseSignedOfflineGrant(body);
}

export async function logoutOnline(): Promise<
  "revoked" | "already-expired"
> {
  const response = await revokeRemoteSessionCookie();
  const body = await readResponseBody(response);
  if (response.status === 204) {
    return "revoked";
  }
  if (response.status === 401) {
    return "already-expired";
  }
  throw responseError(body, response.status);
}

function responseError(body: unknown, status: number): Error {
  return apiError(body, status);
}
