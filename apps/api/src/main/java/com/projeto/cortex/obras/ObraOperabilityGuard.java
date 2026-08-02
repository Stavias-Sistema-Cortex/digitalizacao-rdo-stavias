package com.projeto.cortex.obras;

import com.projeto.cortex.common.SyncConvergenceWindow;
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
        // Dentro da janela de convergência do sync, obra arquivada aceita
        // escrita: o lançamento foi vivido em campo e o dispositivo carrega a
        // única cópia. Fora dela, o arquivamento segue barrando — é a
        // diferença entre convergir um registro e operar uma obra encerrada.
        boolean writable = !normalizedId.isEmpty() && (
                SyncConvergenceWindow.isOpen()
                        ? obraRepository.findExistingIdForShare(normalizedId)
                        .isPresent()
                        : obraRepository.findWritableIdForShare(normalizedId)
                        .isPresent()
        );
        if (!writable) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Obra não encontrada ou arquivada."
            );
        }
    }
}
