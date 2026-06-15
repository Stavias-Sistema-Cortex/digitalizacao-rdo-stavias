package com.projeto.cortex.obras;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ObraRepository extends JpaRepository<Obra, String> {

    boolean existsByCodigoContrato(String codigoContrato);

    Optional<Obra> findByCodigoContrato(String codigoContrato);

    @Query("""
            SELECT o
            FROM Obra o
            WHERE o.arquivadoEm IS NULL
            ORDER BY o.atualizadoEm DESC, o.id DESC
            """)
    List<Obra> listar(Pageable pageable);

    @Query("""
            SELECT o
            FROM Obra o
            WHERE o.arquivadoEm IS NULL
              AND (
                    LOWER(o.codigoContrato) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(o.codigoCw) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(o.codigoInterno) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(o.nome) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(o.cliente) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(o.cidade) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(o.rodovia) LIKE LOWER(CONCAT('%', :query, '%'))
              )
            ORDER BY o.atualizadoEm DESC, o.id DESC
            """)
    List<Obra> buscar(String query, Pageable pageable);
}
