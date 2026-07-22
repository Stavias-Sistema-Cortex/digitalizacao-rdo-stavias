import type { ConversaLocalRecord, ConversaTipo } from "../../lib/db/db.types";
import { activeParticipant } from "./mensagensView";

/**
 * As três figuras dos avatares. `obra` é a silhueta de capacete: EQUIPE e OBRA
 * são os canais presos a um canteiro, e o capacete é o que os separa de um
 * grupo qualquer sem precisar de rótulo.
 */
export type AvatarSilhueta = "pessoa" | "dupla" | "obra";

/** Tintas de canteiro: teal da marca, sálvia, areia, barro e pedra. */
export const AVATAR_TINTAS = 5;

export function silhuetaDaConversa(tipo: ConversaTipo): AvatarSilhueta {
  if (tipo === "DIRETA") return "pessoa";
  if (tipo === "GRUPO") return "dupla";
  return "obra";
}

/**
 * Semente da conversa — sempre um id, nunca o nome: renomear a obra não pode
 * trocar a cor que a pessoa já aprendeu. Na conversa direta é o id do outro, e
 * não o da conversa: senão a mesma pessoa sairia de uma cor na lista e de outra
 * nas bolhas dela, e a tinta deixaria de valer como reconhecimento.
 */
export function sementeDaConversa(
  conversation: ConversaLocalRecord,
  currentUserId: string,
): string {
  if (conversation.tipo !== "DIRETA") {
    return conversation.id;
  }
  const outro = conversation.participantes
    .filter(activeParticipant)
    .find((participant) => participant.colaboradorId !== currentUserId);
  return outro?.colaboradorId ?? conversation.id;
}

/**
 * Tinta estável a partir da semente. A silhueta sozinha não separa uma conversa
 * da outra — sem a tinta a lista viraria uma parede de discos iguais e perderia
 * o reconhecimento de relance que as iniciais davam.
 */
export function tintaDoAvatar(seed: string): number {
  let hash = 0;
  for (const char of seed) {
    hash = (hash * 31 + (char.codePointAt(0) ?? 0)) % 100_000_007;
  }
  return hash % AVATAR_TINTAS;
}
