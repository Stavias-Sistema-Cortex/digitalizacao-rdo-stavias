package com.projeto.cortex.intelligence.stavia.knowledge.worksite;

import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaWorksiteReader
        implements WorksiteReader {

    private final ObraRepository obraRepository;

    public JpaWorksiteReader(
            ObraRepository obraRepository
    ) {
        this.obraRepository = obraRepository;
    }

    @Override
    public Optional<WorksiteSnapshot> findById(
            String worksiteId
    ) {
        if (
                worksiteId == null
                || worksiteId.isBlank()
        ) {
            return Optional.empty();
        }

        return obraRepository
                .findById(worksiteId.trim())
                .map(this::toSnapshot);
    }

    private WorksiteSnapshot toSnapshot(
            Obra obra
    ) {
        return new WorksiteSnapshot(
                obra.getId(),
                obra.getCodigoContrato(),
                obra.getCodigoCw(),
                obra.getCodigoInterno(),
                obra.getNome(),
                obra.getCliente(),
                obra.getDescricao(),
                obra.getCidade(),
                obra.getUf(),
                obra.getRodovia(),
                obra.getStatus(),
                obra.getFonteCriacao(),
                obra.getCriadoEm(),
                obra.getAtualizadoEm(),
                obra.getArquivadoEm(),
                obra.getVersaoLinha()
        );
    }
}
