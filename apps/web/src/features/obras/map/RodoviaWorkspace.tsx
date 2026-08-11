import { useCallback, useEffect, useMemo, useState } from "react";

import type { CameraDaObra } from "./cameraDaObra";

import { SYNC_COMPLETED_EVENT } from "../../../lib/sync/syncEvents";
import { getSession, isAlfa } from "../../auth/authSession";
import { CampoDeExtremo } from "./CampoDeExtremo";
import { LeafletTrechoMap } from "./LeafletTrechoMap";
import {
  RASCUNHO_VAZIO,
  type ExtremoDoTrecho,
  type PontoGeografico,
  type RascunhoDoTrecho,
} from "./rascunhoDoTrecho";
import { OperationalMap } from "./OperationalMap";
import {
  alternarCategoria,
  categoriasDaColecao,
  FILTRO_VAZIO,
  filtrarColecao,
  type FiltroDoMapa,
} from "./filtrosDoMapa";
import { rotuloDaCategoria } from "./mapCategories";
import {
  CADASTRO_VAZIO,
  FAIXAS_INTERDITAVEIS,
  STATUS_DO_TRECHO,
  validarCadastro,
  type CadastroTrecho,
  type StatusTrechoCadastrado,
} from "../trecho/trechoCadastrado";
import {
  buildOperationalFeatureCollection,
  comprimentoAproximadoM,
  isValidWorksiteCoordinate,
  type OperationalFeature,
  type OperationalFeatureCollection,
  type WorksiteMapPoint,
} from "./mapGeometry";
import { carregarMapaObra, type LeituraMapaObra } from "./obraMapApi";
import {
  encerrarGeometria,
  redesenharTrecho,
  registrarEixoDaObra,
  registrarPontoDeCampo,
  registrarTrechoDesenhado,
  type GeometriaVisivelNoMapa,
} from "./obraGeometriaMutations";
import { apoiarTrechosNoEixo, lerEixoDaColecao } from "./eixoDaObra";
import { corrigirKmPeloMapa } from "./corrigirKmPeloMapa";
import type { SegmentoTrecho } from "../trecho/trechoGeometry";
import { quilometroDigitado } from "../../../lib/numeros/quilometroDigitado";
import { hojeIso } from "./execucaoDoTrecho";
import { resolverRdoDoTrecho } from "./rdoDoTrechoDesenhado";
import {
  execucaoDoTrechoDesenhado,
  propriedadesDaFormaDesenhada,
} from "./trechoAlimentaORdo";
import { getLocalRdo } from "../../../lib/db/rdoRepository";
import {
  rdoDraftFromLocalRecord,
  saveExistingRdoDraftAtomically,
} from "../../../lib/db/localRdoService";
import type { RdoDraft } from "../../rdos/rdo.types";
import { createAndPersistLocalPendingRdoDraft } from "../../rdos/rdoDraftCreation";
import {
  CapturaDeCampoError,
  lerPosicaoDeCampo,
} from "./capturaDeCampo";
import {
  buscarEnquadramentoAproximado,
  type EnderecoDaObra,
  type EnquadramentoAproximado,
} from "./enquadramentoAproximado";
import "./RodoviaWorkspace.css";

interface RodoviaWorkspaceProps {
  obra: WorksiteMapPoint;
  /** Somente Alfa pode desenhar o trecho contratual. */
  podeDesenhar: boolean;
  /**
   * Endereço cadastral, usado apenas para abrir o mapa na região certa
   * enquanto a obra não tem coordenada nem geometria.
   */
  endereco?: EnderecoDaObra;
  /**
   * Apontamentos do trecho, já recortados pelo período que o esquemático
   * aplica. É deles que saem as linhas apoiadas no eixo — o quilômetro
   * declarado no RDO desenhando no mapa sem que ninguém tenha desenhado ali.
   */
  segmentos?: readonly SegmentoTrecho[];
  /**
   * Dia observado no esquemático. Quando o operador escolhe um dia lá, o mapa
   * o acompanha: duas metades da mesma tela mostrando dias diferentes seria
   * pior do que não filtrar.
   */
  dataObservada?: string | null;
}

type EstadoLeitura =
  | { fase: "carregando" }
  | { fase: "pronto"; leitura: LeituraMapaObra }
  | { fase: "erro"; mensagem: string };

function primeiraCoordenada(
  colecao: OperationalFeatureCollection,
): [number, number] | null {
  function visitar(valor: unknown): [number, number] | null {
    if (
      Array.isArray(valor) &&
      valor.length >= 2 &&
      typeof valor[0] === "number" &&
      typeof valor[1] === "number"
    ) {
      return [valor[0], valor[1]];
    }
    if (Array.isArray(valor)) {
      for (const filho of valor) {
        const encontrado = visitar(filho);
        if (encontrado) return encontrado;
      }
    }
    return null;
  }

  for (const feature of colecao.features) {
    const encontrado = visitar(feature.geometry.coordinates);
    if (encontrado) return encontrado;
  }
  return null;
}

function formatarInstante(valor: string | null): string {
  if (!valor) {
    return "ainda não sincronizado";
  }
  const data = new Date(valor);
  if (Number.isNaN(data.getTime())) {
    return "ainda não sincronizado";
  }
  return `${data.toLocaleDateString("pt-BR")} às ${data.toLocaleTimeString(
    "pt-BR",
    { hour: "2-digit", minute: "2-digit" },
  )}`;
}

/**
 * O porquê que fica gravado quando alguém tira um ponto do mapa.
 *
 * <p>O encerramento continua exigindo motivo, porque é registro e registro sem
 * porquê não explica nada a quem ler depois. O que saiu foi a exigência de
 * digitá-lo: cobrar uma justificativa escrita para tirar uma marcação feita no
 * lugar errado é atrito onde não ajuda ninguém, e o registro já carrega quem
 * removeu, quando e qual ponto.
 */
const MOTIVO_DA_REMOCAO_NO_MAPA =
  "Geometria removida do mapa por quem a revisou.";

/**
 * O porquê que fica gravado quando alguém acerta um traçado torto.
 *
 * <p>Pela mesma razão do encerramento: a alteração geográfica é registro e
 * precisa de motivo, mas exigir que ele seja digitado para endireitar uma
 * linha é atrito onde não ajuda. Quem corrigiu, quando, e de qual trecho — o
 * registro já carrega.
 */
const MOTIVO_DA_CORRECAO_NO_MAPA =
  "Traçado corrigido no mapa por quem o revisou.";

/**
 * Os dois extremos de uma linha já desenhada.
 *
 * <p>A correção abre com a linha que existe, e não com a tela em branco: quem
 * vai acertar um traçado quase sempre quer mover um extremo e deixar o outro
 * onde está. Começar do zero obrigaria a remarcar o ponto que já estava certo,
 * que é o mesmo trabalho de apagar e desenhar de novo.
 */
function extremosDaLinha(
  geometry: OperationalFeature["geometry"],
): { inicio: PontoGeografico; fim: PontoGeografico } | null {
  const coordenadas =
    geometry.type === "LineString"
      ? (geometry.coordinates as unknown as [number, number][])
      : geometry.type === "MultiLineString"
        ? (geometry.coordinates as unknown as [number, number][][]).flat()
        : null;
  if (!coordenadas || coordenadas.length < 2) {
    return null;
  }
  const primeira = coordenadas[0];
  const ultima = coordenadas[coordenadas.length - 1];
  return {
    inicio: { lng: primeira[0], lat: primeira[1] },
    fim: { lng: ultima[0], lat: ultima[1] },
  };
}

/** O que o encerramento precisa saber da geometria que está na tela. */
function geometriaVisivel(
  feature: OperationalFeature,
  obraId: string,
): GeometriaVisivelNoMapa {
  const propriedades = feature.properties as Record<string, unknown>;
  const texto = (chave: string): string | null =>
    typeof propriedades[chave] === "string" && propriedades[chave]
      ? (propriedades[chave] as string)
      : null;
  return {
    id: String(feature.id),
    obraId,
    categoria: String(propriedades.categoria ?? "PONTO_OPERACIONAL"),
    objetoTipo: texto("objetoTipo"),
    objetoId: texto("objetoId"),
    geometry: feature.geometry,
    properties: propriedades,
    fonte: texto("fonte") ?? "DESCONHECIDA",
    versao:
      typeof propriedades.versao === "number" ? propriedades.versao : 0,
    validoDesde: texto("validoDesde") ?? "",
  };
}

/**
 * Mapa da rodovia em duas metades.
 *
 * À esquerda o basemap de satélite do provider configurado; à direita o Leaflet
 * sobre a malha aberta, que continua utilizável mesmo sem chave de provider. Os
 * dois leem exatamente a mesma coleção autoritativa de geometrias, e a leitura
 * se atualiza a cada rodada de sincronização.
 */
export function RodoviaWorkspace({
  obra,
  podeDesenhar,
  endereco,
  segmentos,
  dataObservada,
}: RodoviaWorkspaceProps) {
  const [estado, setEstado] = useState<EstadoLeitura>({ fase: "carregando" });
  const [aviso, setAviso] = useState<string | null>(null);
  const [filtro, setFiltro] = useState<FiltroDoMapa>(FILTRO_VAZIO);
  const [capturando, setCapturando] = useState(false);
  /*
   * Extremos marcados, ainda não gravados.
   *
   * Ficam aqui, e não dentro do mapa, porque são o mesmo dado que o formulário
   * edita e que a linha desenhada representa. Antes o rascunho vivia no mapa e
   * era zerado junto com o modo de desenho: marcar o fim apagava o início da
   * tela sem que ninguém tivesse desistido dele.
   */
  const [rascunho, setRascunho] = useState<RascunhoDoTrecho>(RASCUNHO_VAZIO);
  const [marcando, setMarcando] = useState<ExtremoDoTrecho | null>(null);
  // Cada marcação no mapa remonta os campos de coordenada, que voltam a exibir
  // o que foi clicado sem desfazer o que estivesse sendo digitado antes.
  const [marcacoesNoMapa, setMarcacoesNoMapa] = useState(0);
  const [cadastro, setCadastro] = useState<CadastroTrecho>(CADASTRO_VAZIO);
  const [salvandoCadastro, setSalvandoCadastro] = useState(false);
  /*
   * Cadastro do eixo da obra.
   *
   * Desenhar era tarefa por RDO, e quem apontava pelo quilômetro — que é como
   * a obra fala — não via nada no mapa. O eixo inverte o gesto: a rodovia é
   * traçada uma vez, com o quilômetro de cada ponta, e todo apontamento passa
   * a se apoiar sozinho sobre ela.
   *
   * Reusa a mesma marcação de extremos do trecho de propósito. São dois
   * gestos com a mesma mecânica, e duplicá-la faria os dois divergirem no
   * primeiro acerto de um deles.
   */
  const [cadastrandoEixo, setCadastrandoEixo] = useState(false);
  const [kmDoEixo, setKmDoEixo] = useState({ inicial: "", final: "" });
  const [salvandoEixo, setSalvandoEixo] = useState(false);
  /*
   * Remoção de ponto operacional.
   *
   * encerrarGeometria já existia inteiro — mutação canônica, histórico
   * preservado, motivo obrigatório — e não tinha por onde ser chamado. Uma
   * marcação errada ficava no mapa para sempre, e como o mapa é lido como
   * evidência, ponto que ninguém consegue tirar vira afirmação que ninguém
   * consegue desmentir.
   *
   * A porta é a lixeira no balão do próprio ponto, nos dois mapas. A primeira
   * tentativa foi uma lista à parte, e ela não funcionava: todo ponto
   * operacional se chama "Ponto operacional", então a lista repetia o mesmo
   * rótulo dezenas de vezes, com uma data ao lado, e ninguém conseguia dizer
   * qual daquelas linhas era a marcação errada. Escolher o ponto é olhar para
   * o mapa.
   *
   * A segunda tentativa pediu o motivo por escrito. Encerrar continua sendo
   * registro, mas cobrar uma justificativa digitada para tirar uma marcação
   * feita no lugar errado é atrito no lugar errado: o que se lê depois é quem
   * removeu, quando, e de qual ponto — e isso o próprio registro já carrega.
   */
  const [pontoParaRemover, setPontoParaRemover] = useState<string | null>(null);
  const [removendo, setRemovendo] = useState(false);
  const [erroDaRemocao, setErroDaRemocao] = useState<string | null>(null);
  /*
   * Removidos nesta sessão.
   *
   * O encerramento é gravado no dispositivo e sobe depois; até subir, o
   * servidor continua respondendo o ponto como ativo. Guardar quem já saiu
   * tira a marcação da tela na hora, nos dois painéis, em vez de deixá-la
   * desenhada até a fila andar — que é o que fazia a remoção parecer que não
   * tinha funcionado.
   */
  const [encerradosAgora, setEncerradosAgora] = useState<string[]>([]);
  const [aproximado, setAproximado] =
    useState<EnquadramentoAproximado | null>(null);
  const [ciclo, setCiclo] = useState(0);
  /*
   * Travamento dos dois mapas.
   *
   * Eles mostram a mesma obra por meios diferentes, e ler os dois lado a lado
   * exigia arrastar cada um até coincidirem — trabalho manual refeito a cada
   * mudança de trecho. Ligado, mover um leva o outro junto.
   *
   * A origem viaja com o enquadramento e serve a uma coisa só: um mapa nunca
   * recebe de volta a câmera que ele mesmo publicou. Sem isso, a devolução
   * chegaria como movimento novo e o par entraria em oscilação — que é o modo
   * clássico de dois mapas espelhados travarem a tela.
   */
  const [travado, setTravado] = useState(false);
  const [camera, setCamera] = useState<
    { valor: CameraDaObra; origem: "vetorial" | "leaflet" } | null
  >(null);

  const aoMoverVetorial = useCallback((valor: CameraDaObra) => {
    setCamera({ valor, origem: "vetorial" });
  }, []);
  const aoMoverLeaflet = useCallback((valor: CameraDaObra) => {
    setCamera({ valor, origem: "leaflet" });
  }, []);

  // A rodovia do cadastro da obra abre o formulário já preenchida: quem
  // desenha o trecho está na mesma rodovia que a obra declara, e redigitar o
  // nome é onde nascem duas grafias para a mesma pista.
  const rodoviaDaObra = endereco?.rodovia?.trim() || null;

  const recarregar = useCallback(() => {
    setCiclo((anterior) => anterior + 1);
  }, []);

  // Cada rodada de sincronização automática relê as camadas autoritativas, que
  // é como o mapa se mantém atual sem canal dedicado de tempo real.
  useEffect(() => {
    window.addEventListener(SYNC_COMPLETED_EVENT, recarregar);
    return () => {
      window.removeEventListener(SYNC_COMPLETED_EVENT, recarregar);
    };
  }, [recarregar]);

  // As dependências são os campos da obra, não o objeto: a página monta um
  // literal novo a cada render, e depender da identidade dele refaria a
  // consulta e a transação no IndexedDB a cada tecla digitada na busca.
  const { id: obraId, nome: obraNome } = obra;
  const { latitude, longitude } = obra;

  useEffect(() => {
    let cancelado = false;
    carregarMapaObra({ id: obraId, nome: obraNome, latitude, longitude })
      .then((leitura) => {
        if (!cancelado) setEstado({ fase: "pronto", leitura });
      })
      .catch((motivo: unknown) => {
        if (cancelado) return;
        setEstado({
          fase: "erro",
          mensagem:
            motivo instanceof Error
              ? motivo.message
              : "Camadas geoespaciais indisponíveis.",
        });
      });
    return () => {
      cancelado = true;
    };
  }, [obraId, obraNome, latitude, longitude, ciclo]);

  const leitura = estado.fase === "pronto" ? estado.leitura : null;
  const worksite = useMemo(
    () =>
      leitura?.dados.obra ?? {
        id: obraId,
        nome: obraNome,
        latitude,
        longitude,
      },
    [leitura?.dados.obra, obraId, obraNome, latitude, longitude],
  );
  /*
   * A leitura sem o que acabou de ser removido.
   *
   * O corte é feito aqui, antes de qualquer painel, e não em cada mapa: os
   * dois precisam enxergar exatamente a mesma coisa, e é isso que faz remover
   * num deles remover no outro na mesma hora.
   */
  const leituraVisivel = useMemo(() => {
    if (!leitura || encerradosAgora.length === 0) return leitura;
    const removidos = new Set(encerradosAgora);
    return {
      ...leitura,
      dados: {
        ...leitura.dados,
        features: leitura.dados.features.filter(
          (feature) => !removidos.has(feature.id),
        ),
      },
    };
  }, [leitura, encerradosAgora]);

  const colecaoPersistida = useMemo(
    () =>
      buildOperationalFeatureCollection(
        worksite,
        leituraVisivel?.dados.features ?? [],
      ),
    [worksite, leituraVisivel?.dados.features],
  );
  /*
   * O que foi apontado pelo quilômetro entra aqui, apoiado no eixo.
   *
   * Nada é gravado: a linha é derivada na leitura, a partir da régua que o
   * eixo oferece e do quilômetro que já mora no RDO. Guardá-la criaria uma
   * segunda cópia da posição, que passaria a divergir do apontamento no
   * primeiro acerto de um dos dois.
   */
  const colecaoCompleta = useMemo(
    () => apoiarTrechosNoEixo(colecaoPersistida, segmentos ?? []),
    [colecaoPersistida, segmentos],
  );
  const eixo = useMemo(
    () => lerEixoDaColecao(colecaoPersistida),
    [colecaoPersistida],
  );
  // O recorte é decidido aqui, no pai dos dois mapas, e desce pronto para
  // ambos: recortar em cada metade permitiria que elas mostrassem obras
  // diferentes lado a lado.
  const colecao = useMemo(
    () => filtrarColecao(colecaoCompleta, filtro),
    [colecaoCompleta, filtro],
  );

  // O ponto escolhido no mapa, para o painel dizer de qual marcação se trata
  // antes de pedir o motivo.
  const pontoEscolhido = useMemo(
    () =>
      pontoParaRemover
        ? (colecaoCompleta.features.find(
            (feature) => feature.id === pontoParaRemover,
          ) ?? null)
        : null,
    [colecaoCompleta.features, pontoParaRemover],
  );

  /*
   * A lixeira só existe para quem pode usá-la.
   *
   * `ObraMapaService.encerrar` começa com `requireAlfa()`, então o pedido de
   * quem não é Alfa volta 403 — recusa terminal, que a fila não reenvia. O
   * ponto sumia da tela pela máscara otimista e voltava na primeira releitura,
   * para sempre, sem nada explicando por quê. Não oferecer o botão é a única
   * resposta honesta: a autorização de verdade continua sendo a do servidor.
   */
  const podeRemoverPonto = isAlfa(getSession());

  /*
   * O trecho também sai daqui. A medida do dia não vem com ele: o quilômetro
   * mora no apontamento, e a geometria só guarda a forma. Apagar a linha
   * errada não custa o RDO.
   */
  const ehTrecho = pontoEscolhido?.properties.categoria === "TRECHO";

  const pedirRemocaoDoPonto = useCallback((id: string) => {
    setPontoParaRemover(id);
    setErroDaRemocao(null);
    setAviso(null);
  }, []);

  const removerPonto = useCallback(async () => {
    if (!pontoParaRemover) return;
    setRemovendo(true);
    setAviso(null);
    try {
      await encerrarGeometria(
        pontoParaRemover,
        MOTIVO_DA_REMOCAO_NO_MAPA,
        pontoEscolhido ? geometriaVisivel(pontoEscolhido, obra.id) : undefined,
      );
      // Sai da tela agora, nos dois painéis, sem esperar a releitura: o
      // encerramento já está gravado, e um ponto que continua desenhado
      // depois de removido é exatamente o que fazia a ação parecer quebrada.
      setEncerradosAgora((atuais) => [...atuais, pontoParaRemover]);
      setPontoParaRemover(null);
      recarregar();
    } catch (motivo: unknown) {
      // A recusa fica na própria pergunta, e a pergunta continua aberta. Ela
      // já foi para o aviso do topo da página: numa obra com conteúdo, isso
      // fica longe da mão que acabou de clicar, e a remoção que falhou era
      // indistinguível de uma que não fez nada.
      setErroDaRemocao(
        motivo instanceof Error
          ? motivo.message
          : "Não foi possível encerrar a geometria.",
      );
    } finally {
      setRemovendo(false);
    }
  }, [obra.id, pontoEscolhido, pontoParaRemover, recarregar]);

  const categorias = useMemo(
    () => categoriasDaColecao(colecaoCompleta),
    [colecaoCompleta],
  );
  const centro = useMemo(
    () =>
      isValidWorksiteCoordinate(worksite.latitude, worksite.longitude)
        ? ([worksite.longitude, worksite.latitude] as [number, number])
        : primeiraCoordenada(colecao),
    [worksite, colecao],
  );

  /*
   * Sem coordenada e sem geometria, o mapa abre na região que o cadastro
   * declara. É um enquadramento, não uma localização: nada disso é gravado, e
   * a obra continua marcada como não georreferenciada até que alguém desenhe o
   * trecho ou registre a posição em campo.
   */
  const precisaDeEnquadramento = estado.fase !== "carregando" && centro === null;
  const cidade = endereco?.cidade ?? null;
  const uf = endereco?.uf ?? null;
  const rodovia = endereco?.rodovia ?? null;

  useEffect(() => {
    let cancelado = false;
    // A consulta só acontece quando falta coordenada; o resultado é limpo no
    // retorno do efeito, e não por uma escrita síncrona no corpo dele.
    if (precisaDeEnquadramento) {
      buscarEnquadramentoAproximado({ cidade, uf, rodovia })
        .then((resultado) => {
          if (!cancelado) setAproximado(resultado);
        })
        .catch(() => undefined);
    }
    return () => {
      cancelado = true;
      setAproximado(null);
    };
  }, [precisaDeEnquadramento, cidade, uf, rodovia]);

  // Soma o comprimento das linhas persistidas: é a extensão que a obra
  // realmente tem desenhada, distinta da extensão medida nos RDOs.
  const extensaoTrecho = useMemo(() => {
    const total = colecao.features.reduce((acumulado, feature) => {
      const comprimento = comprimentoAproximadoM(feature.geometry);
      return comprimento === null ? acumulado : acumulado + comprimento;
    }, 0);
    return total > 0 ? total : null;
  }, [colecao.features]);

  /*
   * O dia escolhido no esquemático recorta o mapa junto.
   *
   * Duas metades da mesma tela mostrando dias diferentes seria pior do que não
   * filtrar nada. A escolha de fora é acompanhada durante a própria
   * renderização, e não por efeito: sincronizar estado por efeito encadeia uma
   * segunda renderização a cada troca de dia, com o mapa piscando o dia
   * anterior no meio do caminho.
   *
   * O controle de dia do mapa continua valendo entre uma troca e outra — o
   * acompanhamento só acontece quando o dia de fora muda de verdade.
   */
  const [diaDeFora, setDiaDeFora] = useState(dataObservada ?? "");
  if (dataObservada !== undefined && (dataObservada ?? "") !== diaDeFora) {
    setDiaDeFora(dataObservada ?? "");
    setFiltro((atual) => ({ ...atual, data: dataObservada ?? "" }));
  }

  const emCadastro =
    marcando !== null || rascunho.inicio !== null || rascunho.fim !== null;

  /**
   * Abre a marcação de um extremo.
   *
   * O cadastro é aberto junto, e não depois de fechar a linha: os campos
   * precisam estar visíveis enquanto os pontos caem, para que dê para conferir
   * a coordenada marcada e corrigi-la sem refazer o outro extremo.
   */
  const marcarExtremo = useCallback(
    (extremo: ExtremoDoTrecho) => {
      setAviso(null);
      setCadastro((atual) =>
        atual.rodovia ? atual : { ...atual, rodovia: rodoviaDaObra ?? "" },
      );
      setMarcando((atual) => (atual === extremo ? null : extremo));
    },
    [rodoviaDaObra],
  );

  /**
   * Um extremo marcado no mapa.
   *
   * Marcado o início, o próximo clique cai naturalmente no fim; marcado o fim,
   * a marcação encerra em vez de continuar armada, senão o clique seguinte
   * moveria um ponto que já estava certo.
   */
  const aoMarcarPonto = useCallback(
    (extremo: ExtremoDoTrecho, ponto: PontoGeografico) => {
      setAviso(null);
      setRascunho((atual) =>
        extremo === "INICIO"
          ? { ...atual, inicio: ponto }
          : { ...atual, fim: ponto },
      );
      setMarcacoesNoMapa((anterior) => anterior + 1);
      setMarcando(
        extremo === "INICIO" && rascunho.fim === null ? "FIM" : null,
      );
    },
    [rascunho.fim],
  );

  const alterarExtremo = useCallback(
    (extremo: ExtremoDoTrecho, ponto: PontoGeografico | null) => {
      setRascunho((atual) =>
        extremo === "INICIO"
          ? { ...atual, inicio: ponto }
          : { ...atual, fim: ponto },
      );
    },
    [],
  );

  const extensaoDaLinha = useMemo(
    () =>
      rascunho.inicio && rascunho.fim
        ? comprimentoAproximadoM({
            type: "LineString",
            coordinates: [
              [rascunho.inicio.lng, rascunho.inicio.lat],
              [rascunho.fim.lng, rascunho.fim.lat],
            ],
          })
        : null,
    [rascunho.inicio, rascunho.fim],
  );

  /**
   * Identidade da linha que este desenho declara no RDO.
   *
   * <p>Fica de fora do `cadastro` de propósito: precisa sobreviver a uma
   * tentativa que falhou. Gravar o apontamento e falhar na geometria deixa a
   * linha lançada; sem uma identidade estável, salvar de novo lançaria uma
   * segunda linha do mesmo trecho — e duas declarações do mesmo trabalho
   * descem para o Financeiro como se fossem dois.
   */
  const [localIdDaLinha, setLocalIdDaLinha] = useState(() =>
    crypto.randomUUID(),
  );

  const cancelarCadastro = useCallback(() => {
    setRascunho(RASCUNHO_VAZIO);
    setMarcando(null);
    setCadastro(CADASTRO_VAZIO);
    setLocalIdDaLinha(crypto.randomUUID());
    setAviso(null);
  }, []);

  const abrirCadastroDoEixo = useCallback(() => {
    setAviso(null);
    setCadastro(CADASTRO_VAZIO);
    setRascunho(RASCUNHO_VAZIO);
    setKmDoEixo({ inicial: "", final: "" });
    setCadastrandoEixo(true);
    setMarcando("INICIO");
  }, []);

  const cancelarCadastroDoEixo = useCallback(() => {
    setCadastrandoEixo(false);
    setRascunho(RASCUNHO_VAZIO);
    setMarcando(null);
    setKmDoEixo({ inicial: "", final: "" });
    setAviso(null);
  }, []);

  /**
   * Grava o eixo.
   *
   * O eixo é cadastro da obra, não medida de trabalho: não abre RDO, não
   * lança serviço e não afirma execução nenhuma. Por isso o caminho aqui é
   * bem mais curto que o do trecho desenhado — ele grava uma geometria e para.
   */
  const salvarEixo = useCallback(async () => {
    const { inicio, fim } = rascunho;
    if (!inicio || !fim) {
      setAviso(
        "Marque as duas pontas do eixo antes de cadastrá-lo. Um extremo sozinho não descreve a rodovia.",
      );
      return;
    }
    const kmInicial = quilometroDigitado(kmDoEixo.inicial);
    const kmFinal = quilometroDigitado(kmDoEixo.final);
    if (kmInicial === null || kmFinal === null) {
      setAviso(
        "Informe o quilômetro das duas pontas. Sem eles o eixo não é régua de nada.",
      );
      return;
    }
    if (kmInicial === kmFinal) {
      setAviso(
        "As duas pontas não podem estar no mesmo quilômetro: não haveria como posicionar nada entre elas.",
      );
      return;
    }
    setSalvandoEixo(true);
    try {
      await registrarEixoDaObra({
        obraId: obra.id,
        pontos: [inicio, fim],
        kmInicial,
        kmFinal,
        rodovia: rodoviaDaObra ?? null,
      });
      setCadastrandoEixo(false);
      setRascunho(RASCUNHO_VAZIO);
      setMarcando(null);
      setKmDoEixo({ inicial: "", final: "" });
      setAviso(
        "Eixo cadastrado neste dispositivo. Os trechos apontados por quilômetro já se apoiam nele.",
      );
      recarregar();
    } catch (motivo: unknown) {
      // O que foi marcado permanece: uma falha de gravação não pode custar as
      // duas pontas que acabaram de ser posicionadas na rodovia.
      setAviso(
        motivo instanceof Error
          ? motivo.message
          : "Não foi possível cadastrar o eixo.",
      );
    } finally {
      setSalvandoEixo(false);
    }
  }, [kmDoEixo, obra.id, rascunho, recarregar, rodoviaDaObra]);

  /*
   * Correção do traçado.
   *
   * A lixeira resolvia a linha errada de um jeito só — jogando fora o desenho
   * inteiro. Quem errou um extremo por cinquenta metros tinha que apagar a
   * linha e refazê-la do zero, redigitando rodovia, sentido, faixa e
   * quilômetro para descrever de novo exatamente o mesmo trabalho.
   *
   * Corrigir muda a forma e mais nada. A identidade do desenho é a mesma, o
   * apontamento do RDO não é tocado, e o histórico lê uma correção em vez de
   * um desenho morto e outro nascido.
   */
  const [trechoEmCorrecao, setTrechoEmCorrecao] = useState<string | null>(null);
  const [salvandoCorrecao, setSalvandoCorrecao] = useState(false);
  /*
   * A linha derivada corrige por outro caminho.
   *
   * <p>Ela não tem geometria a reescrever: nasce na leitura, do quilômetro que
   * mora no apontamento. Arrastar seus extremos vira quilômetro pela régua do
   * eixo e é escrito na linha de serviço do RDO — que é onde ele sempre
   * morou. Gravar geometria aqui recriaria a segunda cópia da posição que o
   * eixo existe para evitar.
   */
  const [corrigindoKmDoApontamento, setCorrigindoKmDoApontamento] =
    useState(false);

  const corrigirTracado = useCallback(
    (id: string) => {
      setErroDaRemocao(null);
      setPontoParaRemover(null);
      // Um desenho novo em andamento não é sacrificado em silêncio: o rascunho
      // é o mesmo campo dos dois gestos, e trocá-lo por baixo apagaria da tela
      // extremos que ninguém desistiu de marcar.
      if (!trechoEmCorrecao && emCadastro) {
        setAviso(
          "Termine ou descarte o desenho em andamento antes de corrigir outro trecho.",
        );
        return;
      }
      const feature = colecaoCompleta.features.find(
        (candidata) => candidata.id === id,
      );
      const extremos = feature ? extremosDaLinha(feature.geometry) : null;
      if (!extremos) {
        setAviso("Este desenho não é uma linha com dois extremos.");
        return;
      }
      setCorrigindoKmDoApontamento(
        feature?.properties.derivadoDoEixo === true,
      );
      setAviso(null);
      setTrechoEmCorrecao(id);
      setRascunho(extremos);
      setMarcacoesNoMapa((anterior) => anterior + 1);
      // Nada é remarcado sozinho: quem abre a correção escolhe qual extremo
      // move, e o outro fica onde já estava certo.
      setMarcando(null);
    },
    [colecaoCompleta.features, emCadastro, trechoEmCorrecao],
  );

  const cancelarCorrecao = useCallback(() => {
    setTrechoEmCorrecao(null);
    setCorrigindoKmDoApontamento(false);
    setRascunho(RASCUNHO_VAZIO);
    setMarcando(null);
    setAviso(null);
  }, []);

  const salvarCorrecao = useCallback(async () => {
    const { inicio, fim } = rascunho;
    if (!trechoEmCorrecao || !inicio || !fim) {
      setAviso("O trecho precisa continuar com início e fim para ser corrigido.");
      return;
    }
    setSalvandoCorrecao(true);
    setAviso(null);
    try {
      if (corrigindoKmDoApontamento) {
        /*
         * Corrigir a linha derivada é corrigir o quilômetro do apontamento.
         * O RDO precisa ser da obra aberta no mapa: escrever quilômetro no
         * RDO de outra obra é o tipo de engano que ninguém percebe depois.
         */
        const feature = colecaoCompleta.features.find(
          (candidata) => candidata.id === trechoEmCorrecao,
        );
        const execucaoId = feature?.properties.execucaoId;
        const rdoId = feature?.properties.objetoId;
        if (
          !eixo ||
          typeof execucaoId !== "string" ||
          typeof rdoId !== "string"
        ) {
          throw new Error(
            "Este trecho não diz de qual linha do RDO ele fala.",
          );
        }
        const km = await corrigirKmPeloMapa({
          obraId: obra.id,
          rdoId,
          execucaoId,
          eixo,
          inicio,
          fim,
        });
        setTrechoEmCorrecao(null);
        setCorrigindoKmDoApontamento(false);
        setRascunho(RASCUNHO_VAZIO);
        setMarcando(null);
        setAviso(
          `Quilômetro corrigido no RDO: km ${km.kmInicial} a ${km.kmFinal}.`
            + " A correção sobe sozinha na próxima sincronização.",
        );
        recarregar();
        return;
      }
      await redesenharTrecho({
        featureId: trechoEmCorrecao,
        pontos: [inicio, fim],
        motivo: MOTIVO_DA_CORRECAO_NO_MAPA,
      });
      setTrechoEmCorrecao(null);
      setRascunho(RASCUNHO_VAZIO);
      setMarcando(null);
      setAviso(
        "Traçado corrigido neste dispositivo. Ele sobe sozinho na próxima sincronização.",
      );
      recarregar();
    } catch (motivo: unknown) {
      // Os extremos ficam na tela: uma falha de gravação não pode custar a
      // remarcação que acabou de ser feita.
      setAviso(
        motivo instanceof Error
          ? motivo.message
          : "Não foi possível corrigir o traçado.",
      );
    } finally {
      setSalvandoCorrecao(false);
    }
  }, [
    colecaoCompleta.features,
    corrigindoKmDoApontamento,
    eixo,
    obra.id,
    rascunho,
    recarregar,
    trechoEmCorrecao,
  ]);

  const salvarCadastro = useCallback(async () => {
    const { inicio, fim } = rascunho;
    if (!inicio || !fim) {
      // Um extremo isolado é ponto, não trecho: sem os dois, não há linha a
      // gravar nem extensão a declarar.
      setAviso(
        "Marque o início e o fim do trecho antes de registrar. Um extremo sozinho não descreve um trecho.",
      );
      return;
    }
    const problema = validarCadastro(cadastro);
    if (problema) {
      setAviso(problema);
      return;
    }
    setSalvandoCadastro(true);
    try {
      // Desenhar e apontar são duas portas para o mesmo registro. O
      // quilômetro passa a morar só na linha de execução do RDO: era ele que
      // existia em dois lugares, sem nada que os reconciliasse, e corrigir num
      // lado deixava o outro mentindo.
      const data = hojeIso();
      const { rdoId, criaRdo } = await resolverRdoDoTrecho({
        obraId: obra.id,
        data,
      });
      let rascunhoDoDia: RdoDraft;
      if (criaRdo) {
        // Sem apontamento do dia, o desenho abre um. Nasce pendente de
        // contexto, como qualquer RDO criado sem o recibo da obra em mãos:
        // sobe quando o contexto chegar, e até lá o trabalho já aparece neste
        // aparelho.
        const criado = await createAndPersistLocalPendingRdoDraft(
          {
            obra: {
              id: obra.id,
              codigoContrato: null,
              codigoCw: null,
              nome: obra.nome,
              cliente: null,
              cidade: null,
              uf: null,
              rodovia: null,
              status: null,
            },
            data,
            previousRdo: null,
            previousWorkforce: [],
            programacoes: [],
            colaboradores: [],
            equipamentos: [],
          },
          { draftId: rdoId },
        );
        rascunhoDoDia = criado.draft;
      } else {
        const registro = await getLocalRdo(rdoId);
        if (!registro) {
          throw new Error(
            "O RDO deste dia não está neste aparelho. Abra-o uma vez antes de desenhar.",
          );
        }
        rascunhoDoDia = rdoDraftFromLocalRecord(registro);
      }

      // Substitui em vez de acrescentar quando a linha já está lá: é o que
      // torna repetir o salvamento inofensivo depois de uma falha.
      const linha = execucaoDoTrechoDesenhado({
        cadastro,
        localId: localIdDaLinha,
      });
      const jaLancada = rascunhoDoDia.servicosExecutados.some(
        (servico) => servico.localId === linha.localId,
      );
      await saveExistingRdoDraftAtomically({
        ...rascunhoDoDia,
        servicosExecutados: jaLancada
          ? rascunhoDoDia.servicosExecutados.map((servico) =>
              servico.localId === linha.localId ? linha : servico,
            )
          : [...rascunhoDoDia.servicosExecutados, linha],
      });

      // A geometria guarda a forma, e nada que o apontamento já afirme.
      await registrarTrechoDesenhado({
        obraId: obra.id,
        rdoId,
        pontos: [inicio, fim],
        propriedades: propriedadesDaFormaDesenhada(
          cadastro,
          extensaoDaLinha,
          // O desenho passa a dizer de qual linha do RDO ele fala. É o que
          // permite ao mapa ler o quilômetro lá, em vez de guardar uma
          // segunda cópia dele aqui.
          linha.localId,
        ),
      });
      setRascunho(RASCUNHO_VAZIO);
      setMarcando(null);
      setCadastro(CADASTRO_VAZIO);
      // O próximo desenho é outro trabalho, e por isso outra linha.
      setLocalIdDaLinha(crypto.randomUUID());
      setAviso(
        "Trecho registrado neste dispositivo. Ele sobe sozinho na próxima sincronização.",
      );
      recarregar();
    } catch (motivo: unknown) {
      // O que foi digitado permanece na tela: perder o preenchimento por uma
      // falha de gravação obrigaria a remarcar a linha inteira.
      setAviso(
        motivo instanceof Error
          ? motivo.message
          : "Não foi possível registrar o trecho.",
      );
    } finally {
      setSalvandoCadastro(false);
    }
  }, [
    cadastro,
    extensaoDaLinha,
    localIdDaLinha,
    obra.id,
    obra.nome,
    rascunho,
    recarregar,
  ]);

  /**
   * Registra onde a equipe está agora.
   *
   * A posição vem do sensor do aparelho e é gravada como ponto operacional
   * ligado à obra, passando pela mesma fila de saída das demais mutações: o
   * apontador registra em campo, sem rede, e a evidência sobe sozinha depois.
   */
  const aoCapturarPosicao = useCallback(async () => {
    setAviso(null);
    setCapturando(true);
    try {
      const posicao = await lerPosicaoDeCampo();
      await registrarPontoDeCampo({
        obraId,
        objetoTipo: "OBRA",
        objetoId: obraId,
        latitude: posicao.latitude,
        longitude: posicao.longitude,
        precisaoM: posicao.precisaoM,
        observadoEm: posicao.observadoEm,
      });
      setAviso(
        posicao.precisaoM === null
          ? "Posição registrada neste dispositivo."
          : `Posição registrada com ${Math.round(
              posicao.precisaoM,
            )} m de precisão. Sobe na próxima sincronização.`,
      );
      recarregar();
    } catch (motivo: unknown) {
      setAviso(
        motivo instanceof CapturaDeCampoError || motivo instanceof Error
          ? motivo.message
          : "Não foi possível registrar a posição.",
      );
    } finally {
      setCapturando(false);
    }
  }, [obraId, recarregar]);

  const instrucaoDeGeorreferencia = podeDesenhar
    ? "Use “Desenhar trecho” para marcar o início e o fim sobre a rodovia, ou “Registrar posição” para gravar onde a equipe está agora."
    : "Use “Registrar posição” para gravar onde a equipe está agora; o trecho contratual é desenhado pela administração.";

  return (
    <section className="rodovia-workspace" aria-labelledby="rodovia-workspace-title">
      <header className="rodovia-workspace-header">
        <div>
          <p className="eyebrow">Mapa da rodovia</p>
          <h3 id="rodovia-workspace-title">{worksite.nome}</h3>
          <span>
            última atualização em{" "}
            {formatarInstante(leitura?.obtidoEm ?? null)}
            {leitura?.origem === "CACHE_LOCAL"
              ? " · dados do dispositivo, sem rede"
              : ""}
          </span>
        </div>
        <div className="rodovia-workspace-acoes">
          <button
            type="button"
            className="rodovia-desenho-botao"
            disabled={capturando}
            onClick={() => {
              void aoCapturarPosicao();
            }}
          >
            {capturando ? "Lendo o GPS…" : "Registrar posição"}
          </button>
          {podeDesenhar && !trechoEmCorrecao && !cadastrandoEixo ? (
            <button
              type="button"
              className={
                marcando
                  ? "rodovia-desenho-botao rodovia-desenho-botao--ativo"
                  : "rodovia-desenho-botao"
              }
              aria-pressed={marcando !== null}
              onClick={() => {
                if (marcando) {
                  setMarcando(null);
                  return;
                }
                // Reabrir o desenho começa do início, mas não apaga o que já
                // foi marcado: o rascunho só some por "Descartar".
                marcarExtremo("INICIO");
              }}
            >
              {marcando
                ? "Parar de marcar"
                : emCadastro
                  ? "Continuar o desenho"
                  : "Desenhar trecho"}
            </button>
          ) : null}
          {/*
            O eixo se cadastra uma vez. Com ele de pé, o botão sai da barra:
            oferecê-lo de novo convidaria a uma segunda régua para a mesma
            rodovia, e duas réguas discordando é pior do que nenhuma.
          */}
          {podeDesenhar && !trechoEmCorrecao && !emCadastro && !eixo ? (
            <button
              type="button"
              className={
                cadastrandoEixo
                  ? "rodovia-desenho-botao rodovia-desenho-botao--ativo"
                  : "rodovia-desenho-botao"
              }
              aria-pressed={cadastrandoEixo}
              onClick={() => {
                if (cadastrandoEixo) {
                  cancelarCadastroDoEixo();
                  return;
                }
                abrirCadastroDoEixo();
              }}
            >
              {cadastrandoEixo ? "Parar de marcar" : "Cadastrar o eixo"}
            </button>
          ) : null}
        </div>
      </header>

      {centro ? (
        <dl className="rodovia-workspace-ficha">
          <div>
            <dt>Coordenada da obra</dt>
            <dd>
              {centro[1].toFixed(6)}, {centro[0].toFixed(6)}
              <small>
                {isValidWorksiteCoordinate(
                  worksite.latitude,
                  worksite.longitude,
                )
                  ? " · cadastro da obra"
                  : " · derivada da geometria"}
              </small>
            </dd>
          </div>
          <div>
            <dt>Extensão georreferenciada</dt>
            <dd>
              {extensaoTrecho !== null
                ? `${new Intl.NumberFormat("pt-BR", {
                    maximumFractionDigits: 0,
                  }).format(extensaoTrecho)} m`
                : "—"}
            </dd>
          </div>
          <div>
            <dt>Camadas ativas</dt>
            <dd>{colecao.features.length}</dd>
          </div>
        </dl>
      ) : null}

      {!centro && aproximado ? (
        <p className="rodovia-workspace-aviso rodovia-workspace-aviso--aproximado">
          <strong>Obra ainda não georreferenciada.</strong> O mapa está apenas
          enquadrado em {aproximado.local}, a partir do endereço do cadastro.
          Nada disso é gravado. {instrucaoDeGeorreferencia}
        </p>
      ) : null}

      {aviso ? <p className="rodovia-workspace-aviso">{aviso}</p> : null}
      {estado.fase === "erro" ? (
        <p className="rodovia-workspace-aviso">
          {estado.mensagem} Nenhuma camada foi encontrada neste dispositivo para
          esta obra.
        </p>
      ) : null}

      {/*
        A correção reaproveita os mesmos campos de extremo, e só eles: o que a
        linha representa já está descrito e não muda por ela ter ficado torta.
        Reabrir rodovia, sentido e quilômetro aqui convidaria a reescrever, num
        gesto de geometria, o que o apontamento do RDO afirma.
      */}
      {trechoEmCorrecao ? (
        <form
          className="rodovia-cadastro rodovia-cadastro--correcao"
          aria-label="Correção do traçado do trecho"
          noValidate
          onSubmit={(evento) => {
            evento.preventDefault();
            void salvarCorrecao();
          }}
        >
          <header>
            <div>
              <p className="eyebrow">
                {corrigindoKmDoApontamento
                  ? "Corrigindo o quilômetro"
                  : "Corrigindo o traçado"}
              </p>
              <h3>Marque de novo o extremo que ficou fora do lugar</h3>
            </div>
            <span>
              {extensaoDaLinha === null
                ? "Os dois extremos precisam existir"
                : `${new Intl.NumberFormat("pt-BR", {
                    maximumFractionDigits: 0,
                  }).format(extensaoDaLinha)} m no traçado novo`}
            </span>
          </header>

          <div className="rodovia-cadastro__extremos">
            <CampoDeExtremo
              key={`CORRECAO:INICIO:${marcacoesNoMapa}`}
              extremo="INICIO"
              valor={rascunho.inicio}
              marcando={marcando === "INICIO"}
              onAlterar={(ponto) => alterarExtremo("INICIO", ponto)}
              onMarcarNoMapa={() => marcarExtremo("INICIO")}
            />
            <CampoDeExtremo
              key={`CORRECAO:FIM:${marcacoesNoMapa}`}
              extremo="FIM"
              valor={rascunho.fim}
              marcando={marcando === "FIM"}
              onAlterar={(ponto) => alterarExtremo("FIM", ponto)}
              onMarcarNoMapa={() => marcarExtremo("FIM")}
            />
          </div>

          <footer>
            <button type="button" onClick={cancelarCorrecao}>
              Deixar como está
            </button>
            <button
              type="submit"
              className="is-primary"
              disabled={salvandoCorrecao}
            >
              {salvandoCorrecao
                ? "Corrigindo…"
                : corrigindoKmDoApontamento
                  ? "Salvar o quilômetro no RDO"
                  : "Salvar o traçado"}
            </button>
          </footer>
          <small>
            {corrigindoKmDoApontamento
              ? "Esta linha não é desenho: ela vem do quilômetro apontado no RDO. Mover os extremos reescreve esse quilômetro no apontamento, e nada de geometria é gravado."
              : "É o mesmo trecho: a rodovia, o sentido, a faixa e o apontamento do RDO seguem como estão. Só a forma desenhada muda."}
          </small>
        </form>
      ) : null}

      {/*
        O eixo é a régua da obra, e não um trabalho: ele não abre RDO, não
        lança serviço e não afirma execução nenhuma. Por isso o formulário
        pede duas coisas só — onde a rodovia começa e termina no mapa, e em
        que quilômetro cada ponta está.
      */}
      {cadastrandoEixo ? (
        <form
          className="rodovia-cadastro rodovia-cadastro--eixo"
          aria-label="Cadastro do eixo da obra"
          noValidate
          onSubmit={(evento) => {
            evento.preventDefault();
            void salvarEixo();
          }}
        >
          <header>
            <div>
              <p className="eyebrow">Eixo da obra</p>
              <h3>Trace a rodovia uma vez e diga o quilômetro das pontas</h3>
            </div>
            <span>
              {extensaoDaLinha === null
                ? "Marque as duas pontas do eixo"
                : `${new Intl.NumberFormat("pt-BR", {
                    maximumFractionDigits: 0,
                  }).format(extensaoDaLinha)} m traçados`}
            </span>
          </header>

          <div className="rodovia-cadastro__extremos">
            <CampoDeExtremo
              key={`EIXO:INICIO:${marcacoesNoMapa}`}
              extremo="INICIO"
              valor={rascunho.inicio}
              marcando={marcando === "INICIO"}
              onAlterar={(ponto) => alterarExtremo("INICIO", ponto)}
              onMarcarNoMapa={() => marcarExtremo("INICIO")}
            />
            <CampoDeExtremo
              key={`EIXO:FIM:${marcacoesNoMapa}`}
              extremo="FIM"
              valor={rascunho.fim}
              marcando={marcando === "FIM"}
              onAlterar={(ponto) => alterarExtremo("FIM", ponto)}
              onMarcarNoMapa={() => marcarExtremo("FIM")}
            />
          </div>

          <div className="rodovia-cadastro__grade">
            <label>
              Km na ponta inicial
              <input
                value={kmDoEixo.inicial}
                inputMode="decimal"
                placeholder="172 ou 309+400"
                onChange={(evento) =>
                  setKmDoEixo((atual) => ({
                    ...atual,
                    inicial: evento.target.value,
                  }))}
              />
            </label>
            <label>
              Km na ponta final
              <input
                value={kmDoEixo.final}
                inputMode="decimal"
                placeholder="196"
                onChange={(evento) =>
                  setKmDoEixo((atual) => ({
                    ...atual,
                    final: evento.target.value,
                  }))}
              />
            </label>
          </div>

          <footer>
            <button type="button" onClick={cancelarCadastroDoEixo}>
              Descartar o eixo
            </button>
            <button
              type="submit"
              className="is-primary"
              disabled={salvandoEixo}
            >
              {salvandoEixo ? "Cadastrando…" : "Cadastrar o eixo"}
            </button>
          </footer>
          <small>
            O eixo não lança serviço nem abre RDO — ele é só a régua. Com ele
            de pé, todo trecho apontado por quilômetro aparece no mapa sozinho,
            sem precisar ser desenhado.
          </small>
        </form>
      ) : null}

      {emCadastro && !trechoEmCorrecao && !cadastrandoEixo ? (
        <form
          className="rodovia-cadastro"
          aria-label="Cadastro do trecho desenhado"
          noValidate
          onSubmit={(evento) => {
            evento.preventDefault();
            void salvarCadastro();
          }}
        >
          <header>
            <div>
              <p className="eyebrow">Trecho desenhado</p>
              <h3>Descreva o que esta linha representa</h3>
            </div>
            <span>
              {extensaoDaLinha === null
                ? "Marque os dois extremos para medir a linha"
                : `${new Intl.NumberFormat("pt-BR", {
                    maximumFractionDigits: 0,
                  }).format(extensaoDaLinha)} m desenhados`}
            </span>
          </header>

          {/* Os dois extremos ficam à vista o tempo todo. Cada um se remarca
              sozinho, sem desfazer o outro nem obrigar a recomeçar a linha. */}
          <div className="rodovia-cadastro__extremos">
            <CampoDeExtremo
              key={`INICIO:${marcacoesNoMapa}`}
              extremo="INICIO"
              valor={rascunho.inicio}
              marcando={marcando === "INICIO"}
              onAlterar={(ponto) => alterarExtremo("INICIO", ponto)}
              onMarcarNoMapa={() => marcarExtremo("INICIO")}
            />
            <CampoDeExtremo
              key={`FIM:${marcacoesNoMapa}`}
              extremo="FIM"
              valor={rascunho.fim}
              marcando={marcando === "FIM"}
              onAlterar={(ponto) => alterarExtremo("FIM", ponto)}
              onMarcarNoMapa={() => marcarExtremo("FIM")}
            />
          </div>

          <div className="rodovia-cadastro__grade">
            <label>
              Rodovia
              <input
                value={cadastro.rodovia}
                maxLength={120}
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    rodovia: evento.target.value,
                  }))}
              />
            </label>
            <label>
              Sentido
              <input
                value={cadastro.sentido}
                maxLength={60}
                placeholder="Norte, Sul, Leste…"
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    sentido: evento.target.value,
                  }))}
              />
            </label>
            <label>
              Faixa interditada
              <select
                value={cadastro.faixa}
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    faixa: evento.target.value,
                  }))}
              >
                <option value="">Não declarada</option>
                {FAIXAS_INTERDITAVEIS.map((opcao) => (
                  <option key={opcao.valor} value={opcao.valor}>
                    {opcao.rotulo}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Km inicial
              <input
                value={cadastro.kmInicial}
                inputMode="decimal"
                placeholder="172 ou 309+400"
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    kmInicial: evento.target.value,
                  }))}
              />
            </label>
            <label>
              Km final
              <input
                value={cadastro.kmFinal}
                inputMode="decimal"
                placeholder="171"
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    kmFinal: evento.target.value,
                  }))}
              />
            </label>
            <label>
              Extensão medida (m)
              <input
                value={cadastro.extensaoM}
                inputMode="decimal"
                placeholder={
                  extensaoDaLinha === null
                    ? "opcional"
                    : `${Math.round(extensaoDaLinha)} pela linha`
                }
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    extensaoM: evento.target.value,
                  }))}
              />
            </label>
            <label>
              Situação
              <select
                value={cadastro.status}
                onChange={(evento) =>
                  setCadastro((atual) => ({
                    ...atual,
                    status: evento.target.value as StatusTrechoCadastrado,
                  }))}
              >
                {STATUS_DO_TRECHO.map((opcao) => (
                  <option key={opcao.valor} value={opcao.valor}>
                    {opcao.rotulo}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <footer>
            <button type="button" onClick={cancelarCadastro}>
              Descartar linha
            </button>
            <button
              type="submit"
              className="is-primary"
              disabled={salvandoCadastro}
            >
              {salvandoCadastro ? "Registrando…" : "Registrar trecho"}
            </button>
          </footer>
          <small>
            Nada é gravado enquanto o trecho não for registrado. Marcar de novo
            um extremo substitui apenas aquele ponto.
          </small>
        </form>
      ) : null}

      {categorias.length > 0 ? (
        <div className="rodovia-workspace-filtros">
          <div
            className="rodovia-workspace-filtros__camadas"
            role="group"
            aria-label="Marcações exibidas no mapa"
          >
            {categorias.map((categoria) => {
              const visivel = !filtro.categoriasOcultas.has(categoria);
              return (
                <button
                  key={categoria}
                  type="button"
                  className={visivel ? "is-ativa" : ""}
                  aria-pressed={visivel}
                  onClick={() =>
                    setFiltro((atual) => ({
                      ...atual,
                      categoriasOcultas: alternarCategoria(
                        atual.categoriasOcultas,
                        categoria,
                      ),
                    }))}
                >
                  {rotuloDaCategoria(categoria)}
                </button>
              );
            })}
          </div>
          <label className="rodovia-workspace-filtros__dia">
            <span>Ver o dia</span>
            <input
              type="date"
              value={filtro.data}
              onChange={(event) =>
                setFiltro((atual) => ({
                  ...atual,
                  data: event.target.value,
                }))}
            />
          </label>
          {filtro.data || filtro.categoriasOcultas.size > 0 ? (
            <button
              type="button"
              className="rodovia-workspace-filtros__limpar"
              onClick={() => setFiltro(FILTRO_VAZIO)}
            >
              Mostrar tudo
            </button>
          ) : null}
          <small role="status">
            {filtro.data || filtro.categoriasOcultas.size > 0
              ? `${colecao.features.length} de ${colecaoCompleta.features.length} marcação(ões)`
              : "Sem recorte: o mapa mostra todo o histórico registrado."}
          </small>
        </div>
      ) : null}

      {/*
        O rótulo não muda com o estado, e o estado vive em aria-pressed. Botão
        que troca de nome ao ser apertado obriga a ler duas vezes para saber se
        anuncia o que faz ou o que já é — e, com aria-pressed junto, o leitor de
        tela diz as duas coisas e elas se contradizem.
      */}
      {/*
        Uma pergunta, duas saídas. Aparece só depois da lixeira, sobre o mapa,
        perto de onde o clique aconteceu — e some do jeito que veio.
      */}
      {pontoParaRemover ? (
        <div
          className="rodovia-confirma"
          role="dialog"
          aria-modal="true"
          aria-labelledby="rodovia-confirma-titulo"
          onClick={() => {
            if (!removendo) setPontoParaRemover(null);
          }}
        >
          <div
            className="rodovia-confirma__caixa"
            onClick={(evento) => evento.stopPropagation()}
          >
            <p id="rodovia-confirma-titulo">
              {ehTrecho
                ? "Remover este trecho do mapa?"
                : "Remover este ponto do mapa?"}
            </p>
            {ehTrecho ? (
              <small>
                O apontamento do RDO não é tocado: o quilômetro fica onde está,
                e só o desenho sai do mapa.
              </small>
            ) : null}
            {pontoEscolhido &&
            typeof pontoEscolhido.properties.observadoEm === "string" ? (
              <small>
                Marcado em{" "}
                {formatarInstante(pontoEscolhido.properties.observadoEm)}.
              </small>
            ) : null}
            {erroDaRemocao ? (
              <p className="rodovia-confirma__erro" role="alert">
                {erroDaRemocao}
              </p>
            ) : null}
            <div className="rodovia-confirma__acoes">
              <button
                type="button"
                className="rodovia-confirma__cancelar"
                disabled={removendo}
                onClick={() => setPontoParaRemover(null)}
              >
                {erroDaRemocao ? "Fechar" : "Cancelar"}
              </button>
              <button
                type="button"
                className="rodovia-confirma__remover"
                disabled={removendo}
                onClick={() => void removerPonto()}
              >
                {removendo
                  ? "Removendo…"
                  : erroDaRemocao
                    ? "Tentar de novo"
                    : "Remover"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      <div className="rodovia-workspace-trava">
        <button
          type="button"
          className={
            travado
              ? "rodovia-desenho-botao rodovia-desenho-botao--ativo"
              : "rodovia-desenho-botao"
          }
          aria-pressed={travado}
          title="Mover um mapa leva o outro junto."
          onClick={() => setTravado((atual) => !atual)}
        >
          Travar mapas
        </button>
      </div>

      <div className="rodovia-workspace-split">
        <div className="rodovia-workspace-painel">
          <OperationalMap
            obra={worksite}
            leitura={leituraVisivel}
            filtro={filtro}
            onRemoverPonto={podeRemoverPonto ? pedirRemocaoDoPonto : undefined}
            onRedesenharTrecho={podeDesenhar ? corrigirTracado : undefined}
            carregando={estado.fase === "carregando"}
            erroLeitura={estado.fase === "erro" ? estado.mensagem : null}
            camera={
              travado && camera?.origem === "leaflet" ? camera.valor : null
            }
            onCamera={travado ? aoMoverVetorial : null}
          />
        </div>
        <div className="rodovia-workspace-painel">
          {centro || aproximado ? (
            <LeafletTrechoMap
              features={colecao}
              center={centro ?? (aproximado as EnquadramentoAproximado).centro}
              limitesIniciais={centro ? null : aproximado?.limites ?? null}
              rascunho={rascunho}
              marcando={podeDesenhar ? marcando : null}
              onPontoMarcado={aoMarcarPonto}
              onRemoverPonto={podeRemoverPonto ? pedirRemocaoDoPonto : undefined}
            onRedesenharTrecho={podeDesenhar ? corrigirTracado : undefined}
              camera={
                travado && camera?.origem === "vetorial" ? camera.valor : null
              }
              onCamera={travado ? aoMoverLeaflet : null}
            />
          ) : (
            <div className="rodovia-workspace-vazio">
              <strong>Obra ainda não georreferenciada</strong>
              <p>{instrucaoDeGeorreferencia}</p>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
