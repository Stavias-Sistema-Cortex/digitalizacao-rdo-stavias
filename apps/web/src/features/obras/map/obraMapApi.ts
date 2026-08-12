import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../../lib/api/apiClient";
import { falhaEhAusenciaDeRede } from "./leituraOffline";
import {
  featureDoRegistro,
  contarGeometriasDaObra,
  listarGeometriasLocais,
  reconciliarGeometriasDoServidor,
} from "./obraGeoCacheRepository";
import type {
  ObraMapFeature,
  OperationalGeometry,
  OperationalGeometryType,
  WorksiteMapPoint,
} from "./mapGeometry";

const GEOMETRY_TYPES = new Set<OperationalGeometryType>([
  "Point",
  "MultiPoint",
  "LineString",
  "MultiLineString",
  "Polygon",
  "MultiPolygon",
]);

export interface ObraMapData {
  obra: WorksiteMapPoint;
  features: ObraMapFeature[];
  /**
   * RDOs cuja linha derivada foi calada no servidor.
   *
   * <p>Não é lista de desenho, é lista de silêncio — e ela existe porque o
   * aparelho também deriva linhas a partir dos apontamentos que guarda. Sem
   * ela, o RDO silenciado deixava de constar entre os que o servidor desenha
   * e o aparelho o redesenhava sozinho na leitura seguinte.
   *
   * <p>Opcional porque ausência tem significado próprio e seguro: uma leitura
   * que não fala de silêncio nenhum — a que vem do dispositivo sem rede, ou a
   * de um servidor anterior a esta versão — descreve o mapa como ele sempre
   * foi descrito, e o aparelho desenha o que sabe.
   */
  rdosComLinhaSilenciada?: string[];
}

function objectValue(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function nullableNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value : null;
}

function geometryFromApi(value: unknown): OperationalGeometry | null {
  const object = objectValue(value);
  if (
    typeof object.type !== "string" ||
    !GEOMETRY_TYPES.has(object.type as OperationalGeometryType) ||
    !Array.isArray(object.coordinates)
  ) {
    return null;
  }

  return {
    type: object.type as OperationalGeometryType,
    coordinates: object.coordinates,
  };
}

function featureFromApi(value: unknown): ObraMapFeature | null {
  const object = objectValue(value);
  const geometry = geometryFromApi(object.geometry);
  if (
    typeof object.id !== "string" ||
    !object.id ||
    typeof object.categoria !== "string" ||
    !geometry
  ) {
    return null;
  }

  return {
    id: object.id,
    categoria: object.categoria,
    objetoTipo: nullableString(object.objetoTipo),
    objetoId: nullableString(object.objetoId),
    geometry,
    properties: objectValue(object.properties),
    fonte: nullableString(object.fonte) ?? "DESCONHECIDA",
    versao: nullableNumber(object.versao) ?? 0,
    validoDesde: nullableString(object.validoDesde) ?? "",
    validoAte: nullableString(object.validoAte),
  };
}

export function obraMapResponseFromApi(value: unknown): ObraMapData {
  const root = objectValue(value);
  const obra = objectValue(root.obra);
  const id = nullableString(obra.id);
  const nome = nullableString(obra.nome);
  if (!id || !nome) {
    throw new Error("Resposta do mapa não identifica a obra.");
  }

  return {
    obra: {
      id,
      nome,
      latitude: nullableNumber(obra.latitude),
      longitude: nullableNumber(obra.longitude),
    },
    features: Array.isArray(root.features)
      ? root.features.flatMap((item) => {
          const feature = featureFromApi(item);
          return feature ? [feature] : [];
        })
      : [],
    rdosComLinhaSilenciada: Array.isArray(root.rdosComLinhaSilenciada)
      ? root.rdosComLinhaSilenciada.flatMap((item) =>
          typeof item === "string" && item ? [item] : [],
        )
      : [],
  };
}

/**
 * A lixeira da linha derivada: silêncio no servidor, não apagamento.
 *
 * <p>Chamada direta, fora da fila offline, de propósito: a linha derivada só
 * existe quando o servidor responde — sem rede ela nem aparece no mapa —,
 * então a remoção dela também só existe com rede. O RDO não é tocado, e
 * editá-lo desfaz o silêncio.
 */
export async function silenciarTrechoDerivado(
  obraId: string,
  featureId: string,
  motivo: string,
): Promise<void> {
  const response = await apiFetch(
    `/obras/${encodeURIComponent(obraId)}/geometrias/derivadas/silenciar`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ featureId, motivo }),
    },
  );
  if (!response.ok) {
    const body = await readResponseBody(response);
    throw new Error(responseErrorMessage(body, response.status));
  }
}

export async function buscarMapaObra(obraId: string): Promise<ObraMapData> {
  const response = await apiFetch(
    `/obras/${encodeURIComponent(obraId)}/mapa`,
  );
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  return obraMapResponseFromApi(body);
}

export type OrigemLeituraMapa = "REDE" | "CACHE_LOCAL";

export interface LeituraMapaObra {
  dados: ObraMapData;
  origem: OrigemLeituraMapa;
  /** Instante da última confirmação do servidor para estas camadas. */
  obtidoEm: string | null;
}

/**
 * Lê as camadas da obra combinando servidor e dispositivo.
 *
 * Com rede, a resposta autoritativa reconcilia o armazenamento local e o
 * resultado devolvido já inclui as geometrias desenhadas ou capturadas que ainda
 * não subiram. Sem rede, a leitura vem inteira do dispositivo e a origem é
 * declarada, para que a tela informe que está mostrando o que já tinha em vez de
 * fingir que consultou agora.
 */
/**
 * O que o servidor deriva na leitura e o dispositivo não tem como guardar.
 *
 * <p>A regra "o que o dispositivo sabe manda" existe para o que ele pode ser
 * dono: geometria gravada, que ele cria, encerra e reconcilia. A linha apoiada
 * no eixo não é nada disso — ela nasce na leitura, a partir do quilômetro que
 * mora no RDO, e por isso nunca entra no armazenamento local.
 *
 * <p>Sem esta separação, bastava o aparelho conhecer uma geometria qualquer
 * daquela obra para a resposta inteira do servidor ser descartada, e o trecho
 * apontado por quilômetro sumia do mapa sem nada explicando por quê. Era o
 * caso de toda obra já usada: a localização da própria obra já é uma geometria
 * conhecida.
 */
function derivadas(features: readonly ObraMapFeature[]): ObraMapFeature[] {
  return features.filter(
    (feature) => feature.properties.derivadoDoEixo === true,
  );
}

export async function carregarMapaObra(
  obra: WorksiteMapPoint,
): Promise<LeituraMapaObra> {
  const agora = new Date().toISOString();
  try {
    const dados = await buscarMapaObra(obra.id);
    await reconciliarGeometriasDoServidor(obra.id, dados.features, agora)
      .catch(() => undefined);
    /*
     * O que o dispositivo sabe manda, e "não sei nada" é diferente de "o que
     * havia foi encerrado".
     *
     * Antes bastava a lista de ativas vir vazia para a leitura entregar a
     * resposta crua do servidor. Só que ela vinha vazia justamente depois de
     * remover o último ponto — e o servidor, que ainda não aceitou o
     * encerramento, devolvia o ponto de volta. A remoção era desfeita na tela
     * a cada releitura e a cada F5. Contar os registros, e não só os ativos,
     * separa os dois casos: sem registro nenhum, o aparelho nunca leu esta
     * obra e a resposta do servidor é tudo o que existe.
     */
    const conhecidas = await contarGeometriasDaObra(obra.id).catch(() => 0);
    const locais = conhecidas > 0
      ? await listarGeometriasLocais(obra.id).catch(() => null)
      : [];
    return {
      dados: {
        obra: dados.obra,
        features: locais === null || conhecidas === 0
          ? dados.features
          : [...locais.map(featureDoRegistro), ...derivadas(dados.features)],
        rdosComLinhaSilenciada: dados.rdosComLinhaSilenciada,
      },
      origem: "REDE",
      obtidoEm: agora,
    };
  } catch (reason) {
    // Erro do servidor precisa subir: só a falta de transporte autoriza cair
    // para o que o dispositivo já tinha.
    if (!falhaEhAusenciaDeRede(reason)) {
      throw reason;
    }
    const locais = await listarGeometriasLocais(obra.id).catch(() => null);
    if (locais === null) {
      throw reason;
    }
    const confirmadoEm = locais
      .map((registro) => registro.fetchedAt)
      .filter((valor): valor is string => Boolean(valor))
      .sort()
      .at(-1) ?? null;
    return {
      /*
       * Sem rede não há lista de silêncio, e não há de onde tirá-la: ela não
       * é registro do aparelho. O que o dispositivo desenha então são as suas
       * próprias linhas derivadas, como sempre desenhou — a leitura seguinte,
       * com rede, é quem reimpõe o silêncio.
       */
      dados: {
        obra,
        features: locais.map(featureDoRegistro),
        rdosComLinhaSilenciada: [],
      },
      origem: "CACHE_LOCAL",
      obtidoEm: confirmadoEm,
    };
  }
}
