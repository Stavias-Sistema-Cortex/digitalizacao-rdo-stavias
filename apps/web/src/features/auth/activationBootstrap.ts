import { apiUrl } from "../../lib/api/apiEndpoint";
import { responseErrorCode } from "../../lib/api/apiError";
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
    const response = await fetchImplementation(apiUrl("/auth/session"), {
      method: "GET",
      headers: { Accept: "application/json" },
      credentials: "include",
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
