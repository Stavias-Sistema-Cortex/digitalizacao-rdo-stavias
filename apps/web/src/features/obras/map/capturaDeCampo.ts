export interface PosicaoDeCampo {
  latitude: number;
  longitude: number;
  /** Precisão informada pelo dispositivo, em metros. */
  precisaoM: number | null;
  observadoEm: string;
}

export type FalhaDeCaptura =
  | "SEM_SUPORTE"
  | "PERMISSAO_NEGADA"
  | "POSICAO_INDISPONIVEL"
  | "TEMPO_ESGOTADO";

export class CapturaDeCampoError extends Error {
  readonly motivo: FalhaDeCaptura;

  constructor(motivo: FalhaDeCaptura, message: string) {
    super(message);
    this.name = "CapturaDeCampoError";
    this.motivo = motivo;
  }
}

const MENSAGENS: Record<FalhaDeCaptura, string> = {
  SEM_SUPORTE: "Este dispositivo não expõe localização ao navegador.",
  PERMISSAO_NEGADA:
    "Permissão de localização negada. Autorize o acesso para registrar a posição da frente de serviço.",
  POSICAO_INDISPONIVEL:
    "Não foi possível obter um sinal utilizável. Tente novamente a céu aberto.",
  TEMPO_ESGOTADO:
    "O aparelho não conseguiu fixar a posição a tempo. Tente novamente.",
};

/**
 * Precisão acima da qual a leitura não descreve um ponto de obra.
 *
 * Uma posição com dezenas de metros de incerteza colocaria a frente de serviço
 * na pista errada. É melhor recusar e pedir outra leitura do que gravar uma
 * coordenada que ninguém pode conferir.
 */
export const PRECISAO_MAXIMA_M = 50;

/**
 * Lê a posição real do aparelho.
 *
 * Nada é estimado: sem sensor, sem permissão ou sem sinal utilizável, a leitura
 * falha com um motivo tipado em vez de devolver uma coordenada aproximada.
 */
export function lerPosicaoDeCampo(
  geolocation: Geolocation | undefined = typeof navigator === "undefined"
    ? undefined
    : navigator.geolocation,
): Promise<PosicaoDeCampo> {
  if (!geolocation) {
    return Promise.reject(
      new CapturaDeCampoError("SEM_SUPORTE", MENSAGENS.SEM_SUPORTE),
    );
  }

  return new Promise((resolve, reject) => {
    geolocation.getCurrentPosition(
      (posicao) => {
        const precisao = Number.isFinite(posicao.coords.accuracy)
          ? posicao.coords.accuracy
          : null;
        if (precisao !== null && precisao > PRECISAO_MAXIMA_M) {
          reject(
            new CapturaDeCampoError(
              "POSICAO_INDISPONIVEL",
              `A precisão obtida foi de ${Math.round(
                precisao,
              )} m, acima do limite de ${PRECISAO_MAXIMA_M} m para localizar uma frente de serviço.`,
            ),
          );
          return;
        }
        resolve({
          latitude: posicao.coords.latitude,
          longitude: posicao.coords.longitude,
          precisaoM: precisao,
          observadoEm: new Date(posicao.timestamp).toISOString(),
        });
      },
      (erro) => {
        const motivo: FalhaDeCaptura =
          erro.code === erro.PERMISSION_DENIED
            ? "PERMISSAO_NEGADA"
            : erro.code === erro.TIMEOUT
              ? "TEMPO_ESGOTADO"
              : "POSICAO_INDISPONIVEL";
        reject(new CapturaDeCampoError(motivo, MENSAGENS[motivo]));
      },
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 0 },
    );
  });
}
