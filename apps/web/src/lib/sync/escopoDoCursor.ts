/**
 * Quando o cursor de eventos precisa voltar ao começo.
 *
 * <p>O pull pede ao servidor "o que houve depois do evento N", e o servidor
 * responde com o que esta pessoa alcança <b>agora</b>. As duas coisas juntas
 * abrem um buraco: no dia em que alguém passa a alcançar uma obra que já
 * existia, tudo o que aconteceu nela antes disso está atrás do cursor, e o
 * "depois do evento N" nunca mais o alcança. O aparelho não sabe que perdeu
 * nada — para ele, aqueles eventos simplesmente nunca existiram.
 *
 * <p>RDO e obra se recuperam por outra porta: a lista de RDOs reconcilia contra
 * o servidor e as obras são hidratadas por consulta própria. <b>Tarefa não
 * tem porta nenhuma</b> — ela só entra no aparelho pelo evento. O resultado é
 * o defeito que mais assusta em campo: a equipe inteira conversa sobre uma
 * tarefa que, na tela de quem entrou depois na obra, não existe.
 *
 * <p>A saída é rebobinar o cursor quando o escopo cresce. Rebobinar é seguro:
 * o que já foi aplicado é reconhecido pelo número de commit e pulado, então o
 * custo é de tráfego, não de dado duplicado — e só acontece no dia em que
 * alguém ganha acesso novo, não a cada ciclo.
 */

export interface EscopoDaSessao {
  escopoGlobal: boolean;
  obraIds: readonly string[];
}

/** A marca guardada junto do cursor, estável para a mesma carteira de obras. */
export function marcaDoEscopo(sessao: EscopoDaSessao | null): string {
  if (!sessao) return "";
  if (sessao.escopoGlobal) return "GLOBAL";
  // A lista pode faltar num perfil gravado por uma versão anterior do
  // aplicativo. Sem ela não há escopo a declarar — e inventar um faria a
  // comparação seguinte concluir crescimento onde não houve.
  if (!Array.isArray(sessao.obraIds)) return "";
  const obras = [...new Set(sessao.obraIds.map((id) => id.trim()))]
    .filter((id) => id.length > 0)
    .sort();
  return `OBRAS:${obras.join(",")}`;
}

function obrasDaMarca(marca: string): Set<string> {
  if (!marca.startsWith("OBRAS:")) return new Set();
  const lista = marca.slice("OBRAS:".length);
  if (!lista) return new Set();
  return new Set(lista.split(","));
}

/**
 * O escopo cresceu desde a última vez que este cursor andou?
 *
 * <p>Só crescimento rebobina. Perder acesso a uma obra não deixa buraco
 * nenhum para trás — não há evento a buscar —, e rebobinar por isso faria todo
 * desligamento de alguém custar um recarregamento inteiro sem motivo.
 *
 * <p>Marca ausente é o registro gravado antes desta capacidade existir. Ela
 * não rebobina: sem saber com que escopo o cursor andou, rebobinar seria
 * chutar, e o chute cairia sobre todo mundo de uma vez na primeira abertura
 * depois da atualização.
 */
export function escopoCresceu(
  anterior: string | null | undefined,
  atual: string,
): boolean {
  if (!anterior) return false;
  if (anterior === atual) return false;
  // Quem já alcançava tudo não passa a alcançar mais do que tudo.
  if (anterior === "GLOBAL") return false;
  if (atual === "GLOBAL") return true;

  const antes = obrasDaMarca(anterior);
  for (const obraId of obrasDaMarca(atual)) {
    if (!antes.has(obraId)) return true;
  }
  return false;
}
