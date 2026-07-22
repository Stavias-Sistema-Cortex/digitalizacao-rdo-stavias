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
import {
  apiFetch,
  apiError,
  readResponseBody,
} from "../api/apiClient";

interface RequestOptions extends RequestInit {
  timeoutMs?: number;
}

async function requestJson<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const {
    timeoutMs = 20_000,
    ...fetchOptions
  } = options;

  const response = await apiFetch(path, {
    ...fetchOptions,
    timeoutMs,
    timeoutErrorMessage:
      "Tempo limite excedido ao sincronizar com o Córtex local.",
  });

  const body = await readResponseBody(response);

  if (!response.ok) {
    throw apiError(body, response.status);
  }

  return body as T;
}

function normalizePullResponse(
  raw: SyncPullResponse & {
    events?: unknown[];
  },
): SyncPullResponse {
  const rawEvents = Array.isArray(raw.eventos)
    ? raw.eventos
    : Array.isArray(raw.events)
      ? raw.events
      : [];

  const eventos = rawEvents
    .filter(
      (event): event is Record<string, unknown> =>
        typeof event === "object" &&
        event !== null &&
        !Array.isArray(event),
    )
    .map((event) => {
      const normalized: SyncPullEvent = {
        commitSeq:
          typeof event.commitSeq === "number"
            ? event.commitSeq
            : 0,
        eventoId:
          typeof event.eventoId === "string"
            ? event.eventoId
            : typeof event.id === "string"
              ? event.id
              : "",
        tipoEvento:
          typeof event.tipoEvento === "string"
            ? event.tipoEvento
            : "",
        entidadeTipo:
          typeof event.entidadeTipo === "string"
            ? event.entidadeTipo
            : typeof event.tipoEntidade === "string"
              ? event.tipoEntidade
              : "",
        entidadeId:
          typeof event.entidadeId === "string"
            ? event.entidadeId
            : "",
        ocorridoEmUtc:
          typeof event.ocorridoEmUtc === "string"
            ? event.ocorridoEmUtc
            : undefined,
        criadoEmUtc:
          typeof event.criadoEmUtc === "string"
            ? event.criadoEmUtc
            : undefined,
        versaoEntidade:
          typeof event.versaoEntidade === "number"
            ? event.versaoEntidade
            : null,
        payload:
          typeof event.payload === "object" &&
          event.payload !== null &&
          !Array.isArray(event.payload)
            ? (event.payload as Record<string, unknown>)
            : null,
      };

      return normalized;
    });

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
  dispositivoId: string,
  limit = 100,
): Promise<SyncPullResponse> {
  const query = new URLSearchParams({
    dispositivoId,
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
