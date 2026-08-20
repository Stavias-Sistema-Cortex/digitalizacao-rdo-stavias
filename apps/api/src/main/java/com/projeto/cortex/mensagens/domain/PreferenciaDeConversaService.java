package com.projeto.cortex.mensagens.domain;

import com.projeto.cortex.common.JdbcSavepointBoundary;
import com.projeto.cortex.auth.CurrentUserService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    private static final Duration MAX_FUTURE_CLOCK_SKEW =
            Duration.ofMinutes(5);

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
     * Reabre uma conversa arquivada como se esta pessoa estivesse começando
     * outra vez.
     *
     * <p>A conversa e as mensagens continuam sendo as mesmas no banco. Só a
     * preferência de quem tomou esta iniciativa muda: ela volta para a caixa e
     * a cortina avança para agora. Fazer a atualização apenas quando a linha
     * ainda está arquivada também distingue uma nova intenção de um retry
     * técnico do mesmo pedido.</p>
     */
    @Transactional
    public boolean reiniciarComoNovaSeArquivada(String conversationId) {
        ConversationScope escopo = accessPolicy.requireAccess(conversationId);
        String userId = currentUserService.requireUserId();
        LocalDateTime agora = LocalDateTime.now(ZoneOffset.UTC);
        return jdbcTemplate.update(
                """
                UPDATE conversa_preferencia_pessoal
                SET arquivado_em = NULL,
                    limpo_ate = ?
                WHERE conversa_id = ?
                  AND colaborador_id = ?
                  AND arquivado_em IS NOT NULL
                """,
                agora,
                escopo.id(),
                userId
        ) == 1;
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
        limpar(conversationId, Instant.now());
    }

    @Transactional
    public void limpar(String conversationId, Instant limpoAte) {
        ConversationScope escopo = accessPolicy.requireAccess(conversationId);
        if (limpoAte == null
                || limpoAte.isAfter(Instant.now().plus(MAX_FUTURE_CLOCK_SKEW))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limpoAte deve ser um instante válido e não pode estar no futuro."
            );
        }
        gravarLimpezaMonotona(
                escopo.id(),
                LocalDateTime.ofInstant(limpoAte, ZoneOffset.UTC)
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
     * Um replay atrasado nunca pode puxar a cortina para tras.
     *
     * <p>O gesto inverso continua separado em {@link #desfazerLimpeza(String)}.
     * Assim, duas entregas de "limpar" convergem para o maior instante, e
     * somente um "reabrir" explicito devolve o campo a nulo.</p>
     */
    private void gravarLimpezaMonotona(
            String conversationId,
            LocalDateTime limpoAte
    ) {
        String userId = currentUserService.requireUserId();
        int alteradas = atualizarLimpezaSeAvancar(
                conversationId,
                userId,
                limpoAte
        );
        if (alteradas > 0) return;

        Integer existentes = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM conversa_preferencia_pessoal
                WHERE conversa_id = ? AND colaborador_id = ?
                """,
                Integer.class,
                conversationId,
                userId
        );
        if (existentes != null && existentes > 0) return;

        JdbcSavepointBoundary insertSavepoint = JdbcSavepointBoundary.begin(
                jdbcTemplate,
                "Nao foi possivel proteger a limpeza da conversa."
        );
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO conversa_preferencia_pessoal
                        (id, conversa_id, colaborador_id, limpo_ate)
                    VALUES (?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    conversationId,
                    userId,
                    limpoAte
            );
            insertSavepoint.release(
                    "Nao foi possivel concluir a limpeza da conversa."
            );
        } catch (DuplicateKeyException concorrente) {
            insertSavepoint.rollbackAndRelease(
                    "Nao foi possivel recuperar a limpeza concorrente."
            );
            atualizarLimpezaSeAvancar(conversationId, userId, limpoAte);
        }
    }

    private int atualizarLimpezaSeAvancar(
            String conversationId,
            String userId,
            LocalDateTime limpoAte
    ) {
        return jdbcTemplate.update(
                """
                UPDATE conversa_preferencia_pessoal
                SET limpo_ate = ?
                WHERE conversa_id = ? AND colaborador_id = ?
                  AND (limpo_ate IS NULL OR limpo_ate < ?)
                """,
                limpoAte,
                conversationId,
                userId,
                limpoAte
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

        JdbcSavepointBoundary insertSavepoint = JdbcSavepointBoundary.begin(
                jdbcTemplate,
                "Não foi possível proteger a preferência da conversa."
        );
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
            insertSavepoint.release(
                    "Não foi possível concluir a preferência da conversa."
            );
        } catch (DuplicateKeyException concorrente) {
            insertSavepoint.rollbackAndRelease(
                    "Não foi possível recuperar a preferência concorrente."
            );
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
