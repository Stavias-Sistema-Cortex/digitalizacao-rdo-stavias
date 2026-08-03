/**
 * Teto do escopo de obras que uma sessão carrega.
 *
 * <p>Era 800, repetido em dois lugares, e 800 bastava enquanto a lista trazia
 * só as obras a que a pessoa estava vinculada. A obra deixou de ser
 * compartimento e a lista passou a ser o catálogo inteiro, então o teto virou
 * um penhasco: na obra de número 801 toda sessão que não fosse Alfa passaria a
 * ser recusada de uma vez — não degradaria, quebraria, e no login, que é o pior
 * lugar para descobrir isso.
 *
 * <p>Mora sozinho num módulo sem dependência nenhuma por dois motivos. O
 * número é validado em pontos diferentes do mesmo escopo, e tê-lo escrito duas
 * vezes é como os dois se desencontram — foi assim que o cofre offline ficou
 * tolerando 10.000 enquanto a sessão parava em 800. E, sem importar nada, ele
 * atravessa os testes que trocam a sessão por um dublê sem exigir que o dublê
 * saiba da sua existência.
 */
export const LIMITE_DE_OBRAS_NO_ESCOPO = 10_000;
