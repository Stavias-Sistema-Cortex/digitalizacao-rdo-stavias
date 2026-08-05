import type { ColaboradorLocalRecord } from "../../lib/db/db.types";

/**
 * A parte da busca de pessoas que não depende de tela nem de rede.
 *
 * <p>Separada para poder ser exercitada sozinha: ordenação e recorte são onde
 * um seletor de gente erra de um jeito que ninguém percebe olhando — a pessoa
 * está na lista, só não está onde se olhou.
 */

/**
 * Quantas pessoas a lista mostra de uma vez.
 *
 * <p>Não é o limite da busca: é o quanto cabe na tela sem que rolar vire o
 * trabalho. Quando a busca traz mais que isto, a tela diz que trouxe — o corte
 * silencioso foi o que tornou o seletor anterior enganoso.
 */
export const PESSOAS_VISIVEIS = 40;

/**
 * A partir de quantos caracteres a digitação vai ao servidor.
 *
 * <p>Uma letra só casaria com quase todo mundo e gastaria uma ida à rede para
 * devolver um recorte arbitrário — exatamente o problema que este seletor
 * existe para resolver.
 */
export const MINIMO_PARA_BUSCAR = 2;

/**
 * Normaliza para comparar: sem acento, sem caixa, sem espaço sobrando.
 *
 * <p>Quem digita "jose" precisa achar "JOSÉ", e quem digita no celular em campo
 * raramente acerta o acento. Comparar sem normalizar transformaria o acento num
 * requisito de busca, que é o oposto de ajudar.
 */
export function normalizar(texto: string): string {
  return texto
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim();
}

/**
 * Ordena por onde o trecho digitado aparece no nome.
 *
 * <p>Quem digita "silva" quer ver primeiro quem começa com Silva, não quem tem
 * Silva no meio do quarto sobrenome. Empate desfeito pelo nome, para que a
 * lista não dance entre uma tecla e outra — lista que se reordena sozinha faz
 * a pessoa clicar em quem não queria.
 */
export function ordenarPorRelevancia(
  pessoas: readonly ColaboradorLocalRecord[],
  termo: string,
): ColaboradorLocalRecord[] {
  // Ordena pela primeira palavra digitada: é a que a pessoa considera o começo
  // do nome, e ordenar pelo texto inteiro deixaria de casar assim que a busca
  // passasse a aceitar palavras soltas.
  const alvo = normalizar(termo).split(/\s+/).filter(Boolean)[0] ?? "";
  if (!alvo) {
    return [...pessoas].sort((a, b) => a.nome.localeCompare(b.nome, "pt-BR"));
  }
  return [...pessoas].sort((a, b) => {
    const posicaoA = normalizar(a.nome).indexOf(alvo);
    const posicaoB = normalizar(b.nome).indexOf(alvo);
    const ausenteA = posicaoA < 0 ? Number.MAX_SAFE_INTEGER : posicaoA;
    const ausenteB = posicaoB < 0 ? Number.MAX_SAFE_INTEGER : posicaoB;
    if (ausenteA !== ausenteB) {
      return ausenteA - ausenteB;
    }
    return a.nome.localeCompare(b.nome, "pt-BR");
  });
}

/**
 * Casa contra nome, CPF mascarado e perfil — os três aparecem na linha.
 *
 * <p>Cada palavra digitada é procurada por conta própria. Comparar o texto
 * inteiro como um pedaço contíguo obrigava a acertar o nome na ordem exata e
 * sem pular nada: "paulo neris" não achava "PAULO SERGIO DA SILVA NERIS",
 * porque essas duas palavras nunca aparecem juntas. Só o nome completo servia,
 * o que é o oposto do que uma busca deve pedir — ninguém digita cinco nomes
 * para achar quem já conhece pelo primeiro e pelo último.
 *
 * <p>Exigir que <em>todas</em> as palavras apareçam, e não qualquer uma, é o
 * que mantém a busca útil: cada palavra a mais estreita o resultado, como quem
 * digita espera.
 */
export function correspondeAoTermo(
  pessoa: ColaboradorLocalRecord,
  termo: string,
): boolean {
  const palavras = normalizar(termo).split(/\s+/).filter(Boolean);
  if (palavras.length === 0) {
    return true;
  }
  const campos = [
    normalizar(pessoa.nome),
    normalizar(pessoa.cpfMascarado ?? ""),
    normalizar(pessoa.nomePerfil ?? ""),
  ];
  return palavras.every((palavra) =>
    campos.some((campo) => campo.includes(palavra)),
  );
}

export type ResultadoDaBusca = {
  readonly pessoas: ColaboradorLocalRecord[];
  /** Verdadeiro quando há mais gente do que a lista mostra. */
  readonly excedeu: boolean;
};

/**
 * Filtra, ordena e recorta — devolvendo se recortou.
 *
 * <p>O "excedeu" existe porque a versão anterior deste seletor cortava em
 * cinquenta pessoas sem dizer, e uma lista truncada em silêncio se parece
 * exatamente com uma lista completa.
 */
export function pessoasParaMostrar(
  pessoas: readonly ColaboradorLocalRecord[],
  termo: string,
  visiveis: number = PESSOAS_VISIVEIS,
): ResultadoDaBusca {
  const filtradas = pessoas.filter((pessoa) =>
    correspondeAoTermo(pessoa, termo),
  );
  const ordenadas = ordenarPorRelevancia(filtradas, termo);
  return {
    pessoas: ordenadas.slice(0, visiveis),
    excedeu: ordenadas.length > visiveis,
  };
}

/** Move o destaque sem sair da lista, para a tecla nunca cair no vazio. */
export function proximoDestaque(
  atual: number,
  passo: number,
  total: number,
): number {
  if (total <= 0) {
    return -1;
  }
  if (atual < 0) {
    return passo > 0 ? 0 : total - 1;
  }
  return (atual + passo + total) % total;
}
