import {
  ApiError,
  ApiTransportError,
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

/**
 * Prazo de uma primeira conexão, não de uma resposta quente.
 *
 * Entrar é a primeira coisa que alguém faz no dia, e é exatamente quando a API
 * pode estar fria: o serviço sobe sob demanda e a subida foi medida neste
 * repositório em até 300 segundos — é o prazo que o próprio ping de
 * aquecimento reserva. Desistir aos 20 segundos do padrão devolvia um beco sem
 * saída justamente enquanto o servidor estava subindo.
 *
 * Duas tentativas deste tamanho cobrem aquela medição inteira, e a primeira é
 * o que aciona a subida.
 */
const PRAZO_DE_ENTRADA_MS = 150_000;

function postLogin(cpf: string): Promise<Response> {
  return freshAuthenticationFetch("/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ cpf }),
    timeoutMs: PRAZO_DE_ENTRADA_MS,
    timeoutErrorMessage:
      "O Córtex não respondeu a tempo. Ele pode estar subindo agora; tente entrar outra vez em alguns instantes.",
  });
}

export async function loginWithCpf(cpf: string): Promise<AuthProfile> {
  let response: Response;
  try {
    response = await postLogin(cpf);
  } catch (error: unknown) {
    if (
      !(error instanceof ApiTransportError) ||
      error.kind !== "TIMEOUT"
    ) {
      throw error;
    }
    // A tentativa que estourou o prazo é justamente o que acorda um serviço
    // parado; a segunda costuma responder de imediato. Repetir é seguro:
    // uma entrada abortada não deixou sessão estabelecida neste dispositivo.
    response = await postLogin(cpf);
  }
  const body = await readResponseBody(response);
  if (!response.ok) {
    if (response.status === 401) {
      throw new ApiError("CPF ou acesso inválido.", 401, null);
    }
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
