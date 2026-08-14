package com.projeto.cortex.mensagens.domain;

import com.projeto.cortex.auth.CurrentUserService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Como cada pessoa organiza a própria caixa de mensagens.
 *
 * <p>Arquivar e limpar são gestos de leitor, não de dono: qualquer um pode
 * fazê-los em qualquer conversa que alcance, e nenhum deles muda o que os
 * outros veem. A conversa arquivada por mim continua na lista de todo mundo; o
 * histórico que eu limpei continua inteiro para quem estava junto.
 *
 * <p>Essa assimetria é o ponto. Uma conversa de obra é registro de trabalho —
 * quem esteve onde, o que foi combinado, que foto foi mandada — e some da tela
 * de quem quer arrumação, nunca do banco. Por isso "limpar" guarda um instante
 * em vez de apagar linha: é uma cortina, e a cortina é minha.
 *
 * <p>A preferência não mora em {@code conversa_participante} de propósito. Em
 * conversa de OBRA e de EQUIPE o acesso nasce do vínculo com a obra, e quem é
 * Alfa alcança conversa em que nunca foi inscrito: muita gente que lê uma
 * conversa não tem linha de participante nenhuma. A preferência é de quem lê.
 */
@Service
public class PreferenciaDeConversaService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final ConversaAccessPolicy accessPolicy;

    public PreferenciaDeConversaService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService,
            ConversaAccessPolicy accessPolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.accessPolicy = accessPolicy;
    }

    /** Tira a conversa da minha lista. Ela segue na dos outros. */
    @Transactional
    public void arquivar(String conversationId) {
        ConversationScope escopo = accessPolicy.requireAccess(conversationId);
        gravar(
                escopo.id(),
                "arquivado_em",
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    /** Devolve a conversa à minha lista. */
    @Transactional
    public void desarquivar(String conversationId) {
        ConversationScope escopo = accessPolicy.requireAccess(conversationId);
        gravar(escopo.id(), "arquivado_em", null);
    }

    /**
     * Fecha a cortina sobre o que já passou.
     *
     * <p>O instante é o de agora: mensagem anterior a ele sai da minha vista, e
     * mensagem nova continua chegando. Limpar de novo mais tarde só empurra a
     * cortina — nunca destrói nada, e desfazer é devolver o campo a nulo.
     */
    @Transactional
    public void limpar(String conversationId) {
        ConversationScope escopo = accessPolicy.requireAccess(conversationId);
        gravar(
                escopo.id(),
                "limpo_ate",
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    /** Reabre o histórico inteiro para mim. */
    @Transactional
    public void desfazerLimpeza(String conversationId) {
        ConversationScope escopo = accessPolicy.requireAccess(conversationId);
        gravar(escopo.id(), "limpo_ate", null);
    }

    /** As conversas que esta pessoa tirou da própria lista. */
    public Set<String> conversasArquivadas() {
        String userId = currentUserService.requireUserId();
        Set<String> arquivadas = new LinkedHashSet<>();
        jdbcTemplate.query(
                """
                SELECT conversa_id
                FROM conversa_preferencia_pessoal
                WHERE colaborador_id = ? AND arquivado_em IS NOT NULL
                """,
                rs -> { arquivadas.add(rs.getString("conversa_id")); },
                userId
        );
        return arquivadas;
    }

    /** Até quando cada conversa está limpa, para esta pessoa. */
    public Map<String, LocalDateTime> cortinasDaPessoa() {
        String userId = currentUserService.requireUserId();
        Map<String, LocalDateTime> cortinas = new HashMap<>();
        jdbcTemplate.query(
                """
                SELECT conversa_id, limpo_ate
                FROM conversa_preferencia_pessoal
                WHERE colaborador_id = ? AND limpo_ate IS NOT NULL
                """,
                rs -> {
                    cortinas.put(
                            rs.getString("conversa_id"),
                            rs.getTimestamp("limpo_ate").toLocalDateTime()
                    );
                },
                userId
        );
        return cortinas;
    }

    /** A cortina desta conversa para esta pessoa, ou nulo se não há. */
    public LocalDateTime cortinaDaConversa(String conversationId) {
        String userId = currentUserService.requireUserId();
        return jdbcTemplate.query(
                """
                SELECT limpo_ate
                FROM conversa_preferencia_pessoal
                WHERE colaborador_id = ? AND conversa_id = ?
                LIMIT 1
                """,
                rs -> rs.next() && rs.getTimestamp("limpo_ate") != null
                        ? rs.getTimestamp("limpo_ate").toLocalDateTime()
                        : null,
                userId,
                conversationId
        );
    }

    /**
     * Grava um campo da preferência, criando a linha na primeira vez.
     *
     * <p>O nome do campo é do código, nunca de quem chama — a lista fechada
     * abaixo é o que impede esta concatenação de virar porta de entrada.
     */
    private void gravar(
            String conversationId,
            String campo,
            LocalDateTime valor
    ) {
        if (!"arquivado_em".equals(campo) && !"limpo_ate".equals(campo)) {
            throw new IllegalArgumentException(
                    "Campo de preferência desconhecido: " + campo
            );
        }
        String userId = currentUserService.requireUserId();
        int alteradas = jdbcTemplate.update(
                "UPDATE conversa_preferencia_pessoal SET " + campo + " = ?"
                        + " WHERE conversa_id = ? AND colaborador_id = ?",
                valor,
                conversationId,
                userId
        );
        if (alteradas > 0) return;

        try {
            jdbcTemplate.update(
                    "INSERT INTO conversa_preferencia_pessoal"
                            + " (id, conversa_id, colaborador_id, " + campo + ")"
                            + " VALUES (?, ?, ?, ?)",
                    UUID.randomUUID().toString(),
                    conversationId,
                    userId,
                    valor
            );
        } catch (DuplicateKeyException concorrente) {
            // Dois aparelhos da mesma pessoa arquivando ao mesmo tempo: a linha
            // nasceu entre o UPDATE e o INSERT. O segundo gesto ainda vale.
            jdbcTemplate.update(
                    "UPDATE conversa_preferencia_pessoal SET " + campo + " = ?"
                            + " WHERE conversa_id = ? AND colaborador_id = ?",
                    valor,
                    conversationId,
                    userId
            );
        }
    }
}
