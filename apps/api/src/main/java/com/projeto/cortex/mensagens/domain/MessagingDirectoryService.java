package com.projeto.cortex.mensagens.domain;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.api.MessagingDirectoryResponse;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Quem pode receber uma mensagem: qualquer colega ativo.
 *
 * <p>Antes, achar uma pessoa para conversar dependia do papel de quem
 * procurava. Alfa buscava no catálogo global; todo o resto era obrigado a
 * escolher uma obra primeiro e só enxergava quem estivesse vinculado àquela
 * obra. O efeito prático era o oposto do que uma ferramenta de mensagem existe
 * para fazer: duas pessoas da mesma empresa, em frentes diferentes, não se
 * achavam — e quem estava em campo concluía, com razão, que o sistema só
 * funcionava para quem o administra.
 *
 * <p>Mensagem não é dado de obra. Ela atravessa a operação por natureza, e
 * cercá-la pelo vínculo de obra transformava a cerca de um domínio em cerca de
 * outro. O diretório aqui é plano de propósito.
 *
 * <p>O que continua valendo é o conteúdo: participar de uma conversa é a única
 * credencial que abre suas mensagens e seus anexos, e isso não muda. Poder
 * endereçar não é poder ler.
 */
@Service
public class MessagingDirectoryService {

    /**
     * Teto de resultados. Existe para a busca não virar exportação do quadro de
     * pessoal: quem procura alguém digita um nome, e quem quisesse a lista
     * inteira teria de pedir página por página em vez de levar tudo num pedido.
     */
    private static final int MAX_RESULTS = 50;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public MessagingDirectoryService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    public List<MessagingDirectoryResponse> buscar(String query) {
        String actorId = currentUserService.requireUserId();
        String termo = query == null ? "" : query.trim();

        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.nome, c.nome_perfil
                FROM colaborador c
                WHERE c.ativo = TRUE
                  AND c.deletado_em IS NULL
                  AND c.id <> ?
                """);
        List<Object> parametros = new java.util.ArrayList<>();
        parametros.add(actorId);

        if (!termo.isEmpty()) {
            sql.append("""
                      AND LOWER(COALESCE(c.nome, ''))
                          LIKE LOWER(CONCAT('%', ?, '%')) ESCAPE '!'
                    """);
            parametros.add(escapeLikeLiteral(termo));
        }

        sql.append("ORDER BY c.nome, c.id\nLIMIT ").append(MAX_RESULTS);

        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new MessagingDirectoryResponse(
                        rs.getString("id"),
                        rs.getString("nome"),
                        rs.getString("nome_perfil")
                ),
                parametros.toArray()
        );
    }

    /**
     * Sem isto, digitar {@code %} listaria todo mundo e {@code _} casaria com
     * qualquer letra — o filtro passaria a obedecer a quem digita, em vez de ao
     * que foi digitado.
     */
    private static String escapeLikeLiteral(String value) {
        return value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
                .toLowerCase(Locale.ROOT);
    }
}
