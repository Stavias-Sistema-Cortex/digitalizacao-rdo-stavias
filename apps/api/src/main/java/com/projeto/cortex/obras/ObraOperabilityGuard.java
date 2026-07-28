package com.projeto.cortex.obras;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ObraOperabilityGuard {

    private final ObraRepository obraRepository;

    public ObraOperabilityGuard(ObraRepository obraRepository) {
        this.obraRepository = obraRepository;
    }

    @Transactional(
            propagation = Propagation.MANDATORY,
            noRollbackFor = ResponseStatusException.class
    )
    public void requireWritable(String obraId) {
        String normalizedId = obraId == null ? "" : obraId.trim();
        if (normalizedId.isEmpty()
                || obraRepository.findWritableIdForShare(normalizedId)
                .isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Obra não encontrada ou arquivada."
            );
        }
    }
}
