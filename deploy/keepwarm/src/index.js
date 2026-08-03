/**
 * Toca a API a cada cinco minutos para que ela não hiberne.
 *
 * O alvo é `/api/wake`, e não a prontidão: os dois acordam o contêiner, mas a
 * prontidão cobra junto um round trip completo no object storage e a evidência
 * de release. Repetido 288 vezes por dia, isso é escrita e leitura no bucket
 * sem nenhum leitor. `/api/wake` aquece o que o login precisa — o contêiner de
 * pé, uma conexão no pool e o banco fora da suspensão automática — e nada além.
 *
 * O Worker não decide nada sobre a API e não deve falhar por causa dela: um
 * erro aqui significa apenas que este toque não pegou, e o próximo vem em cinco
 * minutos. O que é registrado serve para diferenciar "não configurado" de "a
 * API não respondeu", que são problemas distintos.
 */

const CAMINHO_DE_AQUECIMENTO = "/api/wake";

// A subida a frio pode levar minutos. Esperar por ela é o ponto: desistir cedo
// deixaria o contêiner subindo pela metade, que é o estado que se quer evitar.
const PRAZO_MS = 120_000;

async function aquecer(env) {
  const origem = (env.CORTEX_WAKE_ORIGIN || "").trim();
  if (!origem) {
    console.warn(
      "CORTEX_WAKE_ORIGIN não configurada: nenhum toque enviado, e a API " +
        "continua pagando a partida a frio no primeiro acesso.",
    );
    return;
  }

  let alvo;
  try {
    const base = new URL(origem);
    if (base.protocol !== "https:") {
      throw new Error("a origem de aquecimento precisa usar HTTPS.");
    }
    alvo = new URL(CAMINHO_DE_AQUECIMENTO, base).toString();
  } catch (erro) {
    console.error(`CORTEX_WAKE_ORIGIN inválida: ${erro.message}`);
    return;
  }

  const abortar = new AbortController();
  const prazo = setTimeout(() => abortar.abort(), PRAZO_MS);
  const inicio = Date.now();
  try {
    const resposta = await fetch(alvo, {
      method: "GET",
      // Acordar o serviço não é, e não pode virar, uma ação autenticada.
      cache: "no-store",
      signal: abortar.signal,
    });
    console.log(
      `aquecimento: ${resposta.status} em ${Date.now() - inicio}ms`,
    );
  } catch (erro) {
    console.warn(
      `aquecimento não completou em ${Date.now() - inicio}ms: ${erro.message}`,
    );
  } finally {
    clearTimeout(prazo);
  }
}

export default {
  async scheduled(_evento, env, ctx) {
    ctx.waitUntil(aquecer(env));
  },
};
