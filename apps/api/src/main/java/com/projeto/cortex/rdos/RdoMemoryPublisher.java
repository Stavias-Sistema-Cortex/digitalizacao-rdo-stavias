package com.projeto.cortex.rdos;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RdoMemoryPublisher {

    private static final String FONTE = "RDO_API";

    private final CortexOperationalMemoryService memoryService;

    public RdoMemoryPublisher(CortexOperationalMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public void registrarRdoCriado(
            String rdoId,
            String obraId,
            String programacaoId,
            String numeroRdo,
            String status
    ) {
        registrarObjetoERelacoes(rdoId, obraId, programacaoId, numeroRdo, status);

        memoryService.registrarEvento(
                "RDO",
                rdoId,
                "RDO_CRIADO",
                FONTE,
                payloadBase(obraId, programacaoId, numeroRdo, status)
        );
    }

    public void registrarRdoEditado(
            String rdoId,
            String obraId,
            String programacaoId,
            String numeroRdo,
            String status
    ) {
        registrarObjetoERelacoes(rdoId, obraId, programacaoId, numeroRdo, status);

        memoryService.registrarEvento(
                "RDO",
                rdoId,
                "RDO_EDITADO",
                FONTE,
                payloadBase(obraId, programacaoId, numeroRdo, status)
        );
    }

    public void registrarRdoEnviado(
            String rdoId,
            String obraId,
            String programacaoId,
            String numeroRdo
    ) {
        registrarObjetoERelacoes(rdoId, obraId, programacaoId, numeroRdo, "ENVIADO");

        memoryService.registrarEvento(
                "RDO",
                rdoId,
                "RDO_ENVIADO",
                FONTE,
                payloadBase(obraId, programacaoId, numeroRdo, "ENVIADO")
        );
    }

    private void registrarObjetoERelacoes(
            String rdoId,
            String obraId,
            String programacaoId,
            String numeroRdo,
            String status
    ) {
        String nome = numeroRdo == null || numeroRdo.isBlank()
                ? "RDO " + rdoId
                : numeroRdo;

        memoryService.registrarObjeto(
                "RDO",
                rdoId,
                numeroRdo,
                nome,
                status,
                FONTE
        );

        memoryService.substituirRelacaoAtiva(
                "RDO",
                rdoId,
                "OBRA",
                obraId,
                "PERTENCE_A",
                FONTE,
                "RDO pertence à obra."
        );

        if (programacaoId != null && !programacaoId.isBlank()) {
            memoryService.substituirRelacaoAtiva(
                    "RDO",
                    rdoId,
                    "PROGRAMACAO_OPERACIONAL",
                    programacaoId,
                    "GERADO_A_PARTIR_DE",
                    FONTE,
                    "RDO gerado a partir da programação operacional."
            );
        }
    }

    private Map<String, Object> payloadBase(
            String obraId,
            String programacaoId,
            String numeroRdo,
            String status
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("obraId", obraId);
        payload.put("programacaoId", programacaoId);
        payload.put("numeroRdo", numeroRdo);
        payload.put("status", status);
        return payload;
    }
}
