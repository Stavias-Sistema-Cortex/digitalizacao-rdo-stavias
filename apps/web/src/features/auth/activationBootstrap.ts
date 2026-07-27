import { apiUrl } from "../../lib/api/apiEndpoint";
import { responseErrorCode } from "../../lib/api/apiError";
import {
  CLIENT_INSTANCE_HEADER,
  getOrCreateClientInstance,
} from "../../lib/api/clientInstance";
import { hasRemoteSessionIsolation } from "./remoteSessionIsolation";

export type ActivationFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

/**
 * This is deliberately dependency-free from the operational application. It
 * decides whether the browser may import the normal Córtex graph at all.
 */
export async function probeActivationOnly(
  fetchImplementation: ActivationFetch = fetch,
): Promise<boolean> {
  if (hasRemoteSessionIsolation()) {
    return false;
  }
  try {
    const headers = new Headers({ Accept: "application/json" });
    const lease = await getOrCreateClientInstance();
    if (lease === null) {
      return false;
    }
    headers.set(CLIENT_INSTANCE_HEADER, lease.value);
    const response = await fetchImplementation(apiUrl("/auth/session"), {
      method: "GET",
      headers,
      // The activation-only response is public state. A newly minted or
      // cloned document may ask for it, but must never attach the shared
      // HttpOnly session cookie before a fresh sign-in binds this proof.
      credentials: lease.requiresFreshAuthentication ? "omit" : "include",
      cache: "no-store",
    });
    if (response.status !== 503) {
      return false;
    }
    return responseErrorCode(await safeJson(response)) ===
      "CORTEX_ACTIVATION_ONLY";
  } catch {
    // A transient network error is handled by the normal login/offline path.
    return false;
  }
}

async function safeJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return null;
  }
}
