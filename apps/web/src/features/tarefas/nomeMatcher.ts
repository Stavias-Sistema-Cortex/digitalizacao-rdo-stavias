/**
 * Reconhecimento de nomes tolerante a caixa, acento e ordem
 * dos tokens — quem digita "JOAO lucas" encontra "João Lucas".
 */

export function normalizeNome(value: string): string {
  return value
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function tokensDe(value: string): string[] {
  const normalized = normalizeNome(value);
  return normalized ? normalized.split(" ") : [];
}

function tokenCasaComNome(
  token: string,
  nomeTokens: string[],
): boolean {
  return nomeTokens.some(
    (nomeToken) =>
      nomeToken === token ||
      nomeToken.startsWith(token),
  );
}

function scoreCandidato(
  consultaTokens: string[],
  nomeNormalizado: string,
): number {
  const consulta = consultaTokens.join(" ");

  if (nomeNormalizado === consulta) {
    return 1000;
  }

  const nomeTokens = nomeNormalizado.split(" ");
  const casados = consultaTokens.filter((token) =>
    tokenCasaComNome(token, nomeTokens),
  ).length;

  if (casados < consultaTokens.length) {
    return 0;
  }

  let score = 100 + casados * 10;

  if (nomeNormalizado.startsWith(consulta)) {
    score += 50;
  }

  // Nomes mais curtos que cobrem a consulta são matches mais
  // específicos do que nomes compostos longos.
  score -= nomeTokens.length;

  return score;
}

export function buscarPorNome<T>(
  candidatos: T[],
  nomeDe: (candidato: T) => string,
  consulta: string,
  limite = 6,
): T[] {
  const consultaTokens = tokensDe(consulta);

  if (consultaTokens.length === 0) {
    return [];
  }

  return candidatos
    .map((candidato) => ({
      candidato,
      score: scoreCandidato(
        consultaTokens,
        normalizeNome(nomeDe(candidato)),
      ),
    }))
    .filter((entry) => entry.score > 0)
    .sort(
      (left, right) =>
        right.score - left.score ||
        nomeDe(left.candidato).localeCompare(
          nomeDe(right.candidato),
          "pt-BR",
        ),
    )
    .slice(0, limite)
    .map((entry) => entry.candidato);
}

/**
 * Resolve o texto digitado para um único candidato quando não
 * há ambiguidade: igualdade normalizada ou um único nome que
 * cobre todos os tokens digitados.
 */
export function reconhecerNomeExato<T>(
  candidatos: T[],
  nomeDe: (candidato: T) => string,
  consulta: string,
): T | null {
  const consultaNormalizada = normalizeNome(consulta);

  if (!consultaNormalizada) {
    return null;
  }

  const exatos = candidatos.filter(
    (candidato) =>
      normalizeNome(nomeDe(candidato)) ===
      consultaNormalizada,
  );

  if (exatos.length === 1) {
    return exatos[0];
  }

  if (exatos.length > 1) {
    return null;
  }

  const cobrindoTokens = buscarPorNome(
    candidatos,
    nomeDe,
    consulta,
    3,
  );

  if (cobrindoTokens.length === 1) {
    return cobrindoTokens[0];
  }

  return null;
}
