package com.projeto.cortex.colaboradores;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Lista os colaboradores ligados a uma obra. Serve o carregamento offline do
 * usuário Beta com o conjunto de colaboradores da obra autorizada, em vez do
 * catálogo global — a autorização de acesso à obra é feita pelo chamador.
 *
 * <p>São três origens: o <b>vínculo explícito</b> com a obra, que é a fonte de
 * autorização do modelo Alfa/Beta, mais o histórico de quem foi alocado ou
 * lançado em RDO dela. A consulta nasceu só com o histórico, então vincular
 * alguém a uma obra pelo Gerir Obras não o fazia aparecer em lugar nenhum que
 * lesse esta lista — Equipes, Tarefas e Mensagens continuavam sem a pessoa que
 * acabara de ser autorizada, e uma obra recém-criada não tinha ninguém para
 * escolher. O histórico continua listado para que nomes já lançados em RDO
 * sigam resolvendo; ele não concede acesso, que é decidido pelo vínculo.</p>
 */
@Service
public class ColaboradorDaObraService {

    private final JdbcTemplate jdbcTemplate;

    public ColaboradorDaObraService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ColaboradorDaObraResponse> listarPorObra(String obraId) {
        return jdbcTemplate.query(
                """
                SELECT DISTINCT
                    c.id,
                    c.nome,
                    c.cpf_mascarado,
                    c.nome_perfil,
                    c.nome_grupo
                FROM colaborador c
                WHERE c.ativo = TRUE
                  AND c.deletado_em IS NULL
                  AND (
                        EXISTS (
                            SELECT 1
                            FROM vinculo_colaborador_obra link
                            WHERE link.colaborador_id = c.id
                              AND link.obra_id = ?
                              AND link.status = 'ATIVO'
                        )
                     OR EXISTS (
                            SELECT 1
                            FROM alocacao_colaborador ac
                            WHERE ac.colaborador_id = c.id
                              AND ac.obra_id = ?
                              AND ac.status <> 'CANCELADA'
                        )
                     OR EXISTS (
                            SELECT 1
                            FROM rdo_mao_obra mo
                            JOIN rdo r ON r.id = mo.rdo_id
                            WHERE mo.colaborador_id = c.id
                              AND r.obra_id = ?
                        )
                  )
                ORDER BY c.nome, c.id
                LIMIT 500
                """,
                (rs, rowNum) -> new ColaboradorDaObraResponse(
                        rs.getString("id"),
                        rs.getString("nome"),
                        rs.getString("cpf_mascarado"),
                        rs.getString("nome_perfil"),
                        rs.getString("nome_grupo")
                ),
                // Três vezes, uma por EXISTS: vínculo, alocação e presença em
                // RDO. Faltava o terceiro, e faltar argumento para um `?` não
                // devolve lista errada — estoura a consulta inteira. Nenhum
                // teste pegava porque todos mockam este serviço.
                obraId,
                obraId,
                obraId
        );
    }

    public ColaboradoresAutorizadosObraResponse listarAutorizados(
            String obraId
    ) {
        Integer total = jdbcTemplate.queryForObject(
                """
                SELECT count(DISTINCT c.id)
                FROM colaborador c
                JOIN vinculo_colaborador_obra link
                  ON link.colaborador_id = c.id
                 AND link.obra_id = ?
                 AND link.status = 'ATIVO'
                WHERE c.ativo = TRUE
                  AND c.deletado_em IS NULL
                """,
                Integer.class,
                obraId
        );
        List<String> ids = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT c.id
                FROM colaborador c
                JOIN vinculo_colaborador_obra link
                  ON link.colaborador_id = c.id
                 AND link.obra_id = ?
                 AND link.status = 'ATIVO'
                WHERE c.ativo = TRUE
                  AND c.deletado_em IS NULL
                ORDER BY c.id
                LIMIT 501
                """,
                String.class,
                obraId
        );
        int exactTotal = total == null ? 0 : total;
        boolean complete = exactTotal <= 500 && ids.size() == exactTotal;
        return new ColaboradoresAutorizadosObraResponse(
                ids.stream().limit(500).toList(),
                exactTotal,
                complete
        );
    }
}
