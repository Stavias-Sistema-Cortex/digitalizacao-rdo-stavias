package com.projeto.cortex.obras.mapa;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * O silêncio de uma linha derivada — a lixeira que não toca no RDO.
 *
 * <p>A linha que o eixo deriva não é registro: nasce na leitura, do quilômetro
 * que o RDO declara. A lixeira dela, portanto, não pode apagar nada — apagar o
 * quilômetro seria apagar o RDO a partir do mapa, e o mapa é projeção, não
 * fato. O que se guarda é o <b>silêncio</b>: aquela linha específica deixa de
 * ser desenhada para todo mundo, e o RDO segue exatamente como estava.
 *
 * <p>O silêncio dura até o RDO falar de novo. Qualquer edição do documento
 * chama {@link #reafirmar} e apaga os silêncios dele — a hierarquia é sempre
 * do RDO, e um documento recém-editado desenha o que declara, ainda que alguém
 * tenha escondido a versão anterior da linha.
 */
@Component
public class TrechoDerivadoSilenciado {

    /** Prefixo da linha derivada de uma execução de serviço. */
    private static final String PREFIXO_EXECUCAO = "eixo:";

    /** Prefixo da linha derivada da interdição declarada na Identificação. */
    private static final String PREFIXO_INTERDICAO = "eixo:rdo:";

    private final JdbcTemplate jdbcTemplate;
    private final CortexOperationalMemoryService memoryService;

    public TrechoDerivadoSilenciado(
            JdbcTemplate jdbcTemplate,
            CortexOperationalMemoryService memoryService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryService = memoryService;
    }

    /**
     * Os RDOs desta obra que têm alguma linha silenciada.
     *
     * <p>Vai na resposta do mapa porque o silêncio não pode valer só para o
     * traço que o servidor deriva. O aparelho deriva os seus também — é assim
     * que o RDO preenchido em campo aparece antes de subir — e ele decide o
     * que desenhar perguntando quais RDOs o servidor já mostra. Sem esta
     * lista, o RDO silenciado sumia daquela conta e o aparelho ressuscitava a
     * linha sozinho: o silêncio durava até a releitura e mais nada.
     *
     * <p>A resposta é por RDO, e não por linha, porque é assim que a supressão
     * já funciona do outro lado: o aparelho não desenha nada de um RDO que o
     * servidor desenha, para a mesma linha não aparecer duas vezes levemente
     * deslocada. Silenciar tem de entrar nessa mesma conta — senão esconder
     * uma linha faria aparecer outra que ninguém tinha visto.
     */
    public List<String> rdosComLinhaSilenciada(String obraId) {
        Set<String> rdos = new LinkedHashSet<>();
        jdbcTemplate.query(
                """
                SELECT DISTINCT rdo_id
                FROM trecho_derivado_silenciado
                WHERE obra_id = ?
                ORDER BY rdo_id
                """,
                rs -> {
                    rdos.add(rs.getString("rdo_id"));
                },
                obraId
        );
        return List.copyOf(rdos);
    }

    /**
     * Silencia a linha derivada identificada pela feição do mapa.
     *
     * <p>Aceita as duas identidades que a derivação emite:
     * {@code eixo:rdo:<rdoId>} para a interdição da Identificação e
     * {@code eixo:<execucaoId>} para a linha de um serviço. Qualquer outra
     * coisa não é linha derivada e devolve {@code false} — quem chamou errou
     * de porta, e a porta certa (encerrar geometria persistida) tem regras
     * próprias que não devem ser contornadas por aqui.
     *
     * <p>Idempotente: silenciar o que já está em silêncio não acumula nada.
     */
    public boolean silenciar(
            String obraId,
            String featureId,
            String motivo,
            String actorId
    ) {
        if (featureId == null) {
            return false;
        }
        String rdoId;
        String execucaoId;
        if (featureId.startsWith(PREFIXO_INTERDICAO)) {
            rdoId = featureId.substring(PREFIXO_INTERDICAO.length()).trim();
            execucaoId = null;
        } else if (featureId.startsWith(PREFIXO_EXECUCAO)) {
            execucaoId = featureId.substring(PREFIXO_EXECUCAO.length()).trim();
            rdoId = rdoDaExecucao(obraId, execucaoId);
        } else {
            return false;
        }
        if (rdoId == null || rdoId.isBlank()) {
            return false;
        }
        if (execucaoId == null && !rdoPertenceAObra(obraId, rdoId)) {
            return false;
        }

        int inseridas = jdbcTemplate.update(
                """
                INSERT INTO trecho_derivado_silenciado
                    (id, obra_id, rdo_id, execucao_id, motivo, silenciado_por)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID().toString(),
                obraId,
                rdoId,
                execucaoId,
                motivo == null || motivo.isBlank() ? null : motivo.trim(),
                actorId
        );
        if (inseridas > 0) {
            registrarNaMemoria(obraId, rdoId, execucaoId, motivo, actorId);
        }
        return true;
    }

    /**
     * O RDO editado reafirma o que declara: os silêncios dele caem.
     *
     * <p>Devolve quantas linhas voltaram a desenhar, para o chamador poder
     * registrar sem consultar de novo.
     */
    public int reafirmar(String rdoId) {
        return jdbcTemplate.update(
                "DELETE FROM trecho_derivado_silenciado WHERE rdo_id = ?",
                rdoId
        );
    }

    private String rdoDaExecucao(String obraId, String execucaoId) {
        if (execucaoId == null || execucaoId.isBlank()) {
            return null;
        }
        return jdbcTemplate.query(
                """
                SELECT rdo_id
                FROM execucao_servico_rdo
                WHERE id = ? AND obra_id = ?
                """,
                rs -> rs.next() ? rs.getString("rdo_id") : null,
                execucaoId,
                obraId
        );
    }

    private boolean rdoPertenceAObra(String obraId, String rdoId) {
        Integer um = jdbcTemplate.query(
                "SELECT 1 FROM rdo WHERE id = ? AND obra_id = ?",
                rs -> rs.next() ? 1 : null,
                rdoId,
                obraId
        );
        return um != null;
    }

    private void registrarNaMemoria(
            String obraId,
            String rdoId,
            String execucaoId,
            String motivo,
            String actorId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("rdoId", rdoId);
        if (execucaoId != null) {
            payload.put("execucaoId", execucaoId);
        }
        if (motivo != null && !motivo.isBlank()) {
            payload.put("motivo", motivo.trim());
        }
        payload.put("silenciadoPor", actorId);
        memoryService.registrarEvento(
                "RDO",
                rdoId,
                "TRECHO_DERIVADO_SILENCIADO",
                "OBRA_MAPA_API",
                obraId,
                payload
        );
    }

}
