import { apiFetch } from "../../lib/api/apiClient";
import { getCortexDb } from "../../lib/db/cortexDb";
import type { TeamDto } from "./teamApi";

/**
 * Apagar a equipe de verdade: no servidor e neste aparelho, com a fila junto.
 *
 * <p>Arquivar não resolvia o caso que doeu: uma equipe com a fila de
 * sincronização doente pendurada nela continuava gerando reenvio e conflito,
 * porque arquivar é só mais uma mutação naquela mesma fila. Apagar remove a
 * causa — a equipe, o registro local e cada mutação que a cita.
 *
 * <p>A ordem é servidor primeiro. Se ele recusar, nada local muda e o motivo
 * aparece; ao contrário, uma recusa deixaria a equipe apagada aqui e viva lá,
 * e a próxima hidratação a traria de volta sem explicação.
 *
 * <p>404 do servidor não é recusa: é a equipe que ele nunca conheceu — criada
 * offline, com a criação ainda presa na fila. Para essa, o gesto é só local, e
 * é exatamente o que se quer: sumir com o rascunho e com a fila dele.
 */
function motivoDaRecusa(corpo: string): string {
  try {
    return String(JSON.parse(corpo)?.message ?? "").trim();
  } catch {
    return corpo.trim();
  }
}

export async function apagarEquipeDefinitivamente(
  team: TeamDto,
): Promise<void> {
  const response = await apiFetch(
    `/equipes/${encodeURIComponent(team.id)}`,
    { method: "DELETE" },
  );
  if (!response.ok && response.status !== 404) {
    throw new Error(
      // A mensagem do servidor é a que explica; trocá-la apagaria a razão.
      motivoDaRecusa(await response.text().catch(() => "")) ||
        "Não foi possível apagar a equipe no servidor.",
    );
  }

  await limparRastroLocalDaEquipe(team.id);
}

/**
 * Tira do aparelho tudo que pertence à equipe: a fila, o histórico, as
 * associações a obras e o próprio registro.
 *
 * <p>É a metade que apagar por fora não faz. Sem ela, a fila continuaria
 * tentando subir mutações de uma equipe que já não existe — e cada uma viraria
 * um conflito novo no ciclo seguinte.
 */
export async function limparRastroLocalDaEquipe(
  teamId: string,
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["teams", "team_history", "team_worksites", "outbox_mutations"],
    "readwrite",
  );

  const outbox = transaction.objectStore("outbox_mutations");
  const mutacoes = await outbox.index("by-entity-id").getAll(teamId);
  for (const mutacao of mutacoes) {
    if ((mutacao.entidadeTipo as string) !== "EQUIPE") continue;
    await outbox.delete(mutacao.clientMutationId);
  }

  // A chave do histórico é composta [equipe, commit]: um intervalo apaga
  // exatamente a faixa da equipe, sem varrer o resto.
  await transaction.objectStore("team_history").delete(
    IDBKeyRange.bound([teamId, -Infinity], [teamId, Infinity]),
  );

  const obras = transaction.objectStore("team_worksites");
  for (const vinculo of await obras.index("by-team-id").getAll(teamId)) {
    await obras.delete((vinculo as { id: string }).id);
  }

  await transaction.objectStore("teams").delete(teamId);
  await transaction.done;
}
