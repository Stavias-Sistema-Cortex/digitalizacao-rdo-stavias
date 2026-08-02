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
        // única cópia. Só a obra inexistente recusa — e diz exatamente isso.
        // A mensagem fundida "não encontrada ou arquivada" escondia qual das
        // duas metades valia, e um dispositivo com quinze registros presos não
        // tinha como saber se faltava restaurar a obra ou se o identificador
        // dela nunca existiu neste banco. Recusa sem causa exata não orienta
        // ninguém.
        if (SyncConvergenceWindow.isOpen()) {
            if (normalizedId.isEmpty()
                    || obraRepository.findExistingIdForShare(normalizedId)
                    .isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Obra não encontrada neste servidor."
                );
            }
            return;
        }
        // Caminho interativo: contrato inalterado, arquivada segue barrando.
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
