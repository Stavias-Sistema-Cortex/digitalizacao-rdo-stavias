package com.projeto.cortex.rdos;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Service
public class RdoContextService {

    private final JdbcTemplate jdbcTemplate;

    public RdoContextService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RdoContextResponse buscarContexto(String obraId, LocalDate data) {
        RdoContextResponse.ObraContexto obra = buscarObra(obraId);

        List<RdoContextResponse.ProgramacaoContexto> programacoes =
                buscarProgramacoesDaObraNaData(obraId, data);

        List<RdoContextResponse.ColaboradorContexto> colaboradores =
                listarColaboradoresAtivos();

        List<RdoContextResponse.EquipamentoContexto> equipamentos =
                listarEquipamentosAtivos();

        return new RdoContextResponse(
                obra,
                data,
                programacoes,
                colaboradores,
                equipamentos
        );
    }

    private RdoContextResponse.ObraContexto buscarObra(String obraId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT
                        id,
                        codigo_contrato,
                        codigo_cw,
                        nome,
                        cliente,
                        cidade,
                        uf,
                        rodovia,
                        status
                    FROM obra
                    WHERE id = ?
                      AND arquivado_em IS NULL
                    """,
                    (rs, rowNum) -> new RdoContextResponse.ObraContexto(
                            rs.getString("id"),
                            rs.getString("codigo_contrato"),
                            rs.getString("codigo_cw"),
                            rs.getString("nome"),
                            rs.getString("cliente"),
                            rs.getString("cidade"),
                            rs.getString("uf"),
                            rs.getString("rodovia"),
                            rs.getString("status")
                    ),
                    obraId
            );
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Obra não encontrada: " + obraId
            );
        }
    }

    private List<RdoContextResponse.ProgramacaoContexto> buscarProgramacoesDaObraNaData(
            String obraId,
            LocalDate data
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    data_programacao,
                    equipe,
                    encarregado,
                    engenheiro,
                    cliente,
                    servico,
                    tipo_servico,
                    cidade,
                    uf,
                    rodovia,
                    sentido,
                    faixa,
                    km_inicial,
                    km_final,
                    extensao_m,
                    largura_m,
                    espessura_cm,
                    area_m2,
                    volume_m3,
                    status
                FROM programacao_operacional
                WHERE obra_id = ?
                  AND data_programacao = ?
                  AND cancelado_em IS NULL
                ORDER BY equipe, servico, km_inicial, id
                """,
                (rs, rowNum) -> new RdoContextResponse.ProgramacaoContexto(
                        rs.getString("id"),
                        rs.getDate("data_programacao").toLocalDate(),
                        rs.getString("equipe"),
                        rs.getString("encarregado"),
                        rs.getString("engenheiro"),
                        rs.getString("cliente"),
                        rs.getString("servico"),
                        rs.getString("tipo_servico"),
                        rs.getString("cidade"),
                        rs.getString("uf"),
                        rs.getString("rodovia"),
                        rs.getString("sentido"),
                        rs.getString("faixa"),
                        rs.getString("km_inicial"),
                        rs.getString("km_final"),
                        rs.getBigDecimal("extensao_m"),
                        rs.getBigDecimal("largura_m"),
                        rs.getBigDecimal("espessura_cm"),
                        rs.getBigDecimal("area_m2"),
                        rs.getBigDecimal("volume_m3"),
                        rs.getString("status")
                ),
                obraId,
                data
        );
    }

    private List<RdoContextResponse.ColaboradorContexto> listarColaboradoresAtivos() {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    codigo_colaborador,
                    nome,
                    email,
                    nome_grupo,
                    nome_perfil,
                    cpf_mascarado
                FROM colaborador
                WHERE ativo = TRUE
                  AND deletado_em IS NULL
                ORDER BY nome
                LIMIT 300
                """,
                (rs, rowNum) -> new RdoContextResponse.ColaboradorContexto(
                        rs.getString("id"),
                        rs.getString("codigo_colaborador"),
                        rs.getString("nome"),
                        rs.getString("email"),
                        rs.getString("nome_grupo"),
                        rs.getString("nome_perfil"),
                        rs.getString("cpf_mascarado")
                )
        );
    }

    private List<RdoContextResponse.EquipamentoContexto> listarEquipamentosAtivos() {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    external_code,
                    name,
                    category
                FROM asset
                WHERE active = TRUE
                  AND deleted_at IS NULL
                ORDER BY external_code, name
                LIMIT 300
                """,
                (rs, rowNum) -> new RdoContextResponse.EquipamentoContexto(
                        rs.getString("id"),
                        rs.getString("external_code"),
                        rs.getString("name"),
                        rs.getString("category")
                )
        );
    }
}
