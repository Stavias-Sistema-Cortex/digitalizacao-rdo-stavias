package com.projeto.cortex.obras;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ObraRepository extends JpaRepository<Obra, String> {

    boolean existsByCodigoContrato(String codigoContrato);

    boolean existsByCodigoContratoAndIdNot(String codigoContrato, String id);

    Optional<Obra> findByCodigoContrato(String codigoContrato);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Obra o WHERE o.id = :id")
    Optional<Obra> findByIdForUpdate(@Param("id") String id);

    @Query(
            value = """
                    SELECT o.id
                    FROM obra o
                    WHERE o.id = :id
                      AND o.arquivado_em IS NULL
                    FOR SHARE
                    """,
            nativeQuery = true
    )
    Optional<String> findWritableIdForShare(@Param("id") String id);

    /**
     * Existência com o mesmo bloqueio compartilhado de
     * {@link #findWritableIdForShare(String)}, sem o filtro de arquivamento.
     * É a leitura da janela de convergência do sync: obra arquivada aceita a
     * escrita, mas a corrida com um arquivar/restaurar simultâneo continua
     * serializada pelo FOR SHARE.
     */
    @Query(
            value = """
                    SELECT o.id
                    FROM obra o
                    WHERE o.id = :id
                    FOR SHARE
                    """,
            nativeQuery = true
    )
    Optional<String> findExistingIdForShare(@Param("id") String id);

    @Query("""
            SELECT o
            FROM Obra o
            WHERE o.arquivadoEm IS NULL
              AND (
                    o.id = :identificador
                 OR o.codigoContrato = :identificador
                 OR o.codigoCw = :identificador
                 OR o.codigoInterno = :identificador
              )
            """)
    List<Obra> findAtivasByIdentificador(String identificador);

    @Query("""
            SELECT o
            FROM Obra o
            WHERE o.id = :identificador
               OR o.codigoContrato = :identificador
               OR o.codigoCw = :identificador
               OR o.codigoInterno = :identificador
            """)
    List<Obra> findByIdentificador(@Param("identificador") String identificador);

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

    @Query("""
            SELECT o
            FROM Obra o
            WHERE o.arquivadoEm IS NOT NULL
            ORDER BY o.atualizadoEm DESC, o.id DESC
            """)
    List<Obra> listarArquivadas(Pageable pageable);

    @Query("""
            SELECT o
            FROM Obra o
            WHERE o.arquivadoEm IS NOT NULL
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
    List<Obra> buscarArquivadas(String query, Pageable pageable);
}
