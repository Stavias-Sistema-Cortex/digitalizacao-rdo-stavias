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
 * Prazo desta sondagem, que roda antes de qualquer pixel aparecer.
 *
 * Nada é desenhado enquanto ela não responde: o documento tem a raiz vazia até
 * o bootstrap decidir o que montar. Sem prazo, um serviço frio segurava a tela
 * em branco pelo tempo que o navegador quisesse esperar — e a pessoa em campo
 * lia isso como aplicativo travado, antes mesmo de ver o campo de CPF.
 *
 * Estourar o prazo não inventa resposta nenhuma: cai no mesmo ramo já usado
 * para falha de rede, que é seguir para a entrada normal. O modo de ativação é
 * a exceção rara; a espera era paga por todo mundo, todo dia.
 */
const PRAZO_DA_SONDAGEM_MS = 6_000;

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
  const abortar = new AbortController();
  const prazo = globalThis.setTimeout(
    () => abortar.abort(),
    PRAZO_DA_SONDAGEM_MS,
  );
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
      signal: abortar.signal,
    });
    if (response.status !== 503) {
      return false;
    }
    return responseErrorCode(await safeJson(response)) ===
      "CORTEX_ACTIVATION_ONLY";
  } catch {
    // A transient network error is handled by the normal login/offline path.
    return false;
  } finally {
    globalThis.clearTimeout(prazo);
  }
}

async function safeJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return null;
  }
}
