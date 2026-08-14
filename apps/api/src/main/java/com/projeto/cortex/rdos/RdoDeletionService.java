package com.projeto.cortex.rdos;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Apagar um RDO pelo caminho de dentro.
 *
 * <p>Existe porque a alternativa era apagar direto no banco, e apagar direto no
 * banco é justamente o que quebra a sincronização: o servidor perde linhas que a
 * fila do aparelho ainda referencia, e no ciclo seguinte cada mutação órfã vira
 * um conflito novo. Pelo endpoint o cliente fica sabendo, limpa a própria fila e
 * a fila continua andando.
 *
 * <p>Três travas, e nenhuma é burocracia:
 *
 * <p>RDO enviado só o Alfa apaga. Rascunho é papel de rascunho — quem monta
 * desfaz. Enviado é registro entregue, e desfazer entrega é decisão de quem
 * responde pela obra.
 *
 * <p>Evidência de receita não passa, para ninguém. Há um gatilho no banco que
 * aborta o DELETE nesse caso; aqui a checagem vem antes só para a pessoa ler o
 * motivo em vez de um erro de banco. Essas linhas são medição que já virou
 * dinheiro.
 *
 * <p>RDO que serve de base para outro não sai antes do outro. Isso é o
 * `previous_rdo_id`, e a ordem importa: apagar o de ontem sem apagar o de hoje
 * deixaria o de hoje sem a origem que ele declara ter.
 */
@Service
public class RdoDeletionService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final RdoMemoryPublisher memoryPublisher;
    private final PrevisaoFinanceiraService previsaoFinanceiraService;

    public RdoDeletionService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService,
            RdoMemoryPublisher memoryPublisher,
            PrevisaoFinanceiraService previsaoFinanceiraService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.memoryPublisher = memoryPublisher;
        this.previsaoFinanceiraService = previsaoFinanceiraService;
    }

    @Transactional
    public RdoDeletionResponse apagar(String rdoId) {
        String id = requireId(rdoId);
        currentUserService.requireUserId();

        Alvo alvo = carregar(id);
        currentUserService.requireWorksiteAccess(alvo.obraId());

        /*
         * Apagar RDO é decisão de Alfa, em qualquer estado. Já houve uma
         * versão desta regra em que rascunho era exceção — "quem monta
         * desfaz" —, e o dono do sistema a revogou: o RDO é o registro do
         * dia da obra, e removê-lo, mesmo rascunho, é mexer no que a
         * operação declara. Quem é Beta descarta apenas o rascunho local
         * que nunca subiu, que o servidor nem conhece.
         */
        currentUserService.requireAlfa();
        recusarSeTemEvidenciaDeReceita(id);
        recusarSeEBaseDeOutro(id);

        /*
         * A partir daqui a ordem é a do grafo de chaves estrangeiras, e cada
         * passo desfaz uma amarra que bloquearia o seguinte. Tudo na mesma
         * transação: ou o RDO some inteiro, ou nada muda.
         */

        // A auto-referência e o laço circular com o snapshot do contexto: o
        // RDO aponta para o recibo e o recibo aponta de volta para o RDO.
        jdbcTemplate.update(
                """
                UPDATE rdo
                SET previous_rdo_id = NULL, creation_context_version = NULL
                WHERE id = ?
                """,
                id
        );

        // Dentro da mão de obra cada linha aponta para a linha equivalente do
        // RDO anterior. Apagar sem desligar isso trava na própria tabela.
        jdbcTemplate.update(
                "UPDATE rdo_mao_obra SET origem_item_id = NULL WHERE rdo_id = ?",
                id
        );
        jdbcTemplate.update(
                """
                UPDATE rdo_mao_obra
                SET origem_item_id = NULL
                WHERE origem_item_id IN (
                    SELECT item.id FROM rdo_mao_obra item WHERE item.rdo_id = ?
                )
                """,
                id
        );

        int anexos = jdbcTemplate.update(
                "DELETE FROM rdo_attachment WHERE rdo_id = ?", id
        );
        jdbcTemplate.update("DELETE FROM rdo_mao_obra WHERE rdo_id = ?", id);
        jdbcTemplate.update("DELETE FROM rdo_equipamento WHERE rdo_id = ?", id);
        jdbcTemplate.update("DELETE FROM rdo_material WHERE rdo_id = ?", id);
        jdbcTemplate.update(
                "DELETE FROM rdo_controle_geometrico WHERE rdo_id = ?", id
        );
        jdbcTemplate.update(
                "DELETE FROM execucao_servico_rdo WHERE rdo_id = ?", id
        );

        int apagados = jdbcTemplate.update("DELETE FROM rdo WHERE id = ?", id);
        if (apagados != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O RDO mudou durante a exclusão. Recarregue e tente de novo."
            );
        }

        memoryPublisher.registrarRdoApagado(
                id, alvo.obraId(), null, alvo.numeroRdo()
        );

        /*
         * Apagar de vez também é mudança de RDO, e a previsão precisa saber.
         *
         * <p>Todo o resto que mexe em RDO — criar, editar, enviar, cancelar,
         * restaurar, importar — recalcula o PDOR aqui mesmo, na volta da
         * operação. Este caminho, que é o mais radical dos sete, era o único
         * que não recalculava: entregava a projeção ao gatilho por evento e
         * mais nada. E o gatilho é melhor esforço — roda numa thread daemon
         * depois de um debounce de cinco segundos e engole a própria falha —,
         * então uma reinicialização no meio da janela perde o recálculo, e
         * nada volta a disparar sozinho. A obra ficava exibindo, sem prazo
         * para sair, a projeção de uma produção que ela mesma apagou.
         *
         * <p>A data de referência vai nula de propósito: o RDO que a daria
         * acabou de deixar de existir, e nula é o pedido para reler a obra
         * como ela está agora.
         */
        previsaoFinanceiraService.recalcularAposMudancaRdo(
                alvo.obraId(), null
        );

        return new RdoDeletionResponse(id, alvo.obraId(), alvo.numeroRdo(), anexos);
    }

    /**
     * O gatilho {@code trg_execucao_rdo_revenue_immutable} já barraria isso,
     * mas como erro de banco no meio da transação. A pessoa merece ler o
     * motivo, e o motivo é bom.
     */
    private void recusarSeTemEvidenciaDeReceita(String rdoId) {
        Integer comEvidencia = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM execucao_servico_rdo
                WHERE rdo_id = ? AND revenue_evidence_id IS NOT NULL
                """,
                Integer.class,
                rdoId
        );
        if (comEvidencia != null && comEvidencia > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Este RDO tem "
                            + comEvidencia
                            + (comEvidencia == 1
                                    ? " serviço já medido" : " serviços já medidos")
                            + " e não pode ser apagado. A medição é registro financeiro."
            );
        }
    }

    private void recusarSeEBaseDeOutro(String rdoId) {
        List<String> dependentes = jdbcTemplate.query(
                """
                SELECT COALESCE(NULLIF(btrim(numero_rdo), ''), id) AS rotulo
                FROM rdo
                WHERE previous_rdo_id = ?
                ORDER BY data_rdo
                LIMIT 5
                """,
                (rs, rowNum) -> rs.getString("rotulo"),
                rdoId
        );
        if (!dependentes.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Este RDO é a base de " + String.join(", ", dependentes)
                            + ". Apague o mais recente primeiro."
            );
        }
    }

    private Alvo carregar(String rdoId) {
        List<Alvo> encontrados = jdbcTemplate.query(
                "SELECT id, obra_id, numero_rdo, status FROM rdo WHERE id = ?",
                (rs, rowNum) -> new Alvo(
                        rs.getString("id"),
                        rs.getString("obra_id"),
                        rs.getString("numero_rdo"),
                        rs.getString("status")
                ),
                rdoId
        );
        if (encontrados.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "RDO não encontrado."
            );
        }
        return encontrados.getFirst();
    }

    private String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "rdoId é obrigatório."
            );
        }
        return value.trim();
    }

    private record Alvo(
            String id,
            String obraId,
            String numeroRdo,
            String status
    ) {
    }
}
