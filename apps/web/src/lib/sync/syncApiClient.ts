import type {
  RegisterDeviceRequest,
  RegisterDeviceResponse,
  SyncAckRequest,
  SyncAckResponse,
  SyncPullEvent,
  SyncPullResponse,
  SyncPushRequest,
  SyncPushResponse,
} from "./sync.types";

const API_PREFIX = "/api";

interface RequestOptions extends RequestInit {
  timeoutMs?: number;
}

async function requestJson<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const {
    timeoutMs = 20_000,
    headers,
    ...fetchOptions
  } = options;

  const controller = new AbortController();
  const timeoutId = window.setTimeout(
    () => controller.abort(),
    timeoutMs,
  );

  try {
    const response = await fetch(`${API_PREFIX}${path}`, {
      ...fetchOptions,
      signal: controller.signal,
      headers: {
        Accept: "application/json",
        ...headers,
      },
    });

    const responseText = await response.text();

    let body: unknown = null;

    if (responseText.trim()) {
      try {
        body = JSON.parse(responseText);
      } catch {
        body = responseText;
      }
    }

    if (!response.ok) {
      const serverMessage =
        typeof body === "object" &&
        body !== null &&
        "message" in body &&
        typeof body.message === "string"
          ? body.message
          : typeof body === "string"
            ? body
            : `HTTP ${response.status}`;

      throw new Error(
        `Falha na API ${response.status}: ${serverMessage}`,
      );
    }

    return body as T;
  } catch (error: unknown) {
    if (
      error instanceof DOMException &&
      error.name === "AbortError"
    ) {
      throw new Error(
        `Tempo limite excedido ao chamar ${path}.`,
      );
    }

    if (error instanceof TypeError) {
      throw new Error(
        "Não foi possível conectar ao backend do Córtex.",
      );
    }

    throw error;
  } finally {
    window.clearTimeout(timeoutId);
  }
}

function normalizePullResponse(
  raw: SyncPullResponse & {
    events?: SyncPullEvent[];
  },
): SyncPullResponse {
  const eventos = Array.isArray(raw.eventos)
    ? raw.eventos
    : Array.isArray(raw.events)
      ? raw.events
      : [];

  return {
    eventos,
    nextCommitSeq:
      typeof raw.nextCommitSeq === "number"
        ? raw.nextCommitSeq
        : eventos.at(-1)?.commitSeq ?? 0,
    hasMore: raw.hasMore === true,
  };
}

export async function registerDeviceApi(
  request: RegisterDeviceRequest,
): Promise<RegisterDeviceResponse> {
  return requestJson<RegisterDeviceResponse>(
    "/sync/dispositivos",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(request),
    },
  );
}

export async function pushMutationsApi(
  request: SyncPushRequest,
): Promise<SyncPushResponse> {
  const response = await requestJson<SyncPushResponse>(
    "/sync/push",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(request),
    },
  );

  if (!Array.isArray(response.resultados)) {
    throw new Error(
      "Resposta inválida do push: resultados ausentes.",
    );
  }

  return response;
}

export async function pullEventsApi(
  afterCommitSeq: number,
  limit = 100,
): Promise<SyncPullResponse> {
  const query = new URLSearchParams({
    afterCommitSeq: String(afterCommitSeq),
    limit: String(limit),
  });

  const response = await requestJson<
    SyncPullResponse & {
      events?: SyncPullEvent[];
    }
  >(`/sync/pull?${query.toString()}`);

  return normalizePullResponse(response);
}

export async function ackCursorApi(
  request: SyncAckRequest,
): Promise<SyncAckResponse> {
  return requestJson<SyncAckResponse>("/sync/ack", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
}
