package com.projeto.cortex.rdos;

import com.projeto.cortex.equipamentos.EquipamentoTerceirizadoService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RdoAssetEligibilityService {

    /**
     * Que máquina pode ser apontada numa obra.
     *
     * <p>A regra era só a segunda metade: existir em {@code
     * asset_obra_eligibilidade} com status ativo. Só que nada popula essa
     * tabela para o parque importado da Zeladoria — nem o conector, nem tela
     * alguma —, então toda obra abria o RDO com a lista de equipamentos vazia e
     * o único caminho era cadastrar a máquina à mão, uma por uma, obra por
     * obra. A máquina existia no Córtex e mesmo assim não dava para apontá-la.
     *
     * <p>Agora vale o mesmo que já vale para a mão de obra: o parque da empresa
     * é da empresa e aparece em qualquer frente; a elegibilidade deixou de
     * decidir quem aparece e passou a decidir quem aparece primeiro.
     *
     * <p>A exceção é a máquina alugada, cadastrada dentro do Córtex por uma obra
     * específica. Ela não é patrimônio da empresa: é um contrato de locação com
     * prazo e dono. Oferecê-la às outras obras encheria a lista de todo mundo
     * com a retroescavadeira que uma frente alugou por três dias — e é
     * exatamente o ruído que o cadastro por obra existia para evitar. Ela
     * continua entrando apenas pela elegibilidade, na obra que a cadastrou.
     *
     * <p>Os dois {@code ?} finais são o par de origem daquele cadastro, nesta
     * ordem, e vêm depois de qualquer outro parâmetro da consulta que use este
     * fragmento.
     */
    static final String APONTAVEL_NA_OBRA = """
            asset.active = TRUE
              AND asset.deleted_at IS NULL
              AND (
                  eligibility.asset_id IS NOT NULL
                  OR asset.source_database <> ?
                  OR asset.source_table <> ?
              )
            """;

    private final JdbcTemplate jdbcTemplate;

    public RdoAssetEligibilityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Recusa o que não pode ser apontado e registra o vínculo do que pode.
     *
     * <p>O nome antigo — "exigir elegível" — descrevia metade do trabalho e
     * escondia a outra. A tabela {@code asset_obra_eligibilidade} não é só um
     * portão de leitura: {@code rdo_equipamento} tem chave estrangeira composta
     * para ela, e sem a linha o banco recusa o INSERT. Enquanto a lista da tela
     * era a própria tabela, a linha sempre existia antes; agora que o parque
     * inteiro pode ser apontado, ela precisa nascer aqui — senão a tela oferece
     * a máquina e a gravação estoura com violação de integridade.
     *
     * <p>Vincular no apontamento é o que a tabela sempre quis dizer: esta
     * máquina trabalha nesta obra. O primeiro RDO que a aponta é exatamente o
     * fato que estabelece isso, e a partir daí ela sobe para o alto da lista,
     * junto com o que já é da obra.
     *
     * <p>Roda dentro da transação de quem chama, então o vínculo só permanece
     * se o RDO permanecer.
     */
    public void garantirElegibilidade(
            String obraId,
            List<RdoCreateRequest.EquipamentoItem> equipamentos
    ) {
        Set<String> checked = new HashSet<>();
        for (RdoCreateRequest.EquipamentoItem item : safe(equipamentos)) {
            String assetId = normalized(item.assetId());
            if (assetId == null || !checked.add(assetId)) {
                continue;
            }
            // A junção externa é a mesma da lista do contexto de propósito: o
            // portão e a tela precisam responder a mesma pergunta, e a maneira
            // de garantir isso é fazerem a mesma pergunta.
            Integer eligible = jdbcTemplate.queryForObject(
                    """
                    SELECT CASE WHEN EXISTS (
                        SELECT 1
                        FROM asset
                        LEFT JOIN asset_obra_eligibilidade eligibility
                          ON eligibility.asset_id = asset.id
                         AND eligibility.obra_id = ?
                         AND eligibility.status = 'ATIVO'
                        WHERE asset.id = ?
                          AND %s
                    ) THEN 1 ELSE 0 END
                    """.formatted(APONTAVEL_NA_OBRA),
                    Integer.class,
                    obraId,
                    assetId,
                    EquipamentoTerceirizadoService.SOURCE_DATABASE,
                    EquipamentoTerceirizadoService.SOURCE_TABLE
            );
            if (eligible == null || eligible != 1) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "O equipamento informado não está no parque ativo "
                                + "disponível para a obra do RDO."
                );
            }
            vincularAObra(obraId, assetId);
        }
    }

    /**
     * O {@code WHERE} do conflito evita reescrever a linha que já está ativa.
     *
     * <p>Sem ele, todo salvamento de RDO tocaria {@code atualizado_em} de cada
     * máquina apontada, e como essa coluna alimenta a versão do contexto de
     * criação, o cache de todo mundo na obra seria invalidado a cada gravação
     * — trabalho de rede para dizer que nada mudou.
     */
    private void vincularAObra(String obraId, String assetId) {
        jdbcTemplate.update(
                """
                INSERT INTO asset_obra_eligibilidade (
                    asset_id, obra_id, status, origem
                )
                VALUES (?, ?, 'ATIVO', 'APONTAMENTO_RDO')
                ON CONFLICT (asset_id, obra_id) DO UPDATE
                SET status = 'ATIVO',
                    atualizado_em = now()
                WHERE asset_obra_eligibilidade.status <> 'ATIVO'
                """,
                assetId,
                obraId
        );
    }

    private List<RdoCreateRequest.EquipamentoItem> safe(
            List<RdoCreateRequest.EquipamentoItem> items
    ) {
        return items == null ? List.of() : items;
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
