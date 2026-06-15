package com.projeto.cortex.programacoes;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProgramacaoOperacionalRepository extends JpaRepository<ProgramacaoOperacional, String> {

    boolean existsByHashOrigem(String hashOrigem);

    @Query("""
            SELECT p
            FROM ProgramacaoOperacional p
            WHERE p.canceladoEm IS NULL
            ORDER BY p.dataProgramacao DESC, p.atualizadoEm DESC, p.id DESC
            """)
    List<ProgramacaoOperacional> listar(Pageable pageable);

    @Query("""
            SELECT p
            FROM ProgramacaoOperacional p
            WHERE p.canceladoEm IS NULL
              AND (
                    LOWER(p.codigoContratoOrigem) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(p.obraNomeOrigem) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(p.equipe) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(p.encarregado) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(p.engenheiro) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(p.cliente) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(p.servico) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(p.tipoServico) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(p.cidade) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(p.rodovia) LIKE LOWER(CONCAT('%', :query, '%'))
              )
            ORDER BY p.dataProgramacao DESC, p.atualizadoEm DESC, p.id DESC
            """)
    List<ProgramacaoOperacional> buscar(String query, Pageable pageable);
}
