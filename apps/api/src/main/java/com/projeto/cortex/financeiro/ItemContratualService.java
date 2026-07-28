package com.projeto.cortex.financeiro;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.ObraRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ItemContratualService {

    private static final String FONTE = "FINANCEIRO_API";

    private final JdbcTemplate jdbcTemplate;
    private final ObraRepository obraRepository;
    private final CortexOperationalMemoryService memoryService;
    private final ObraOperabilityGuard obraOperabilityGuard;

    public ItemContratualService(
            JdbcTemplate jdbcTemplate,
            ObraRepository obraRepository,
            CortexOperationalMemoryService memoryService,
            ObraOperabilityGuard obraOperabilityGuard
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.obraRepository = obraRepository;
        this.memoryService = memoryService;
        this.obraOperabilityGuard = obraOperabilityGuard;
    }

    @Transactional
    public ItemContratualResponse criar(
            String obraIdentifier,
            ItemContratualRequest request
    ) {
        Obra obra = localizarObra(obraIdentifier);
        validar(request);
        obraOperabilityGuard.requireWritable(obra.getId());

        BigDecimal quantidade =
                escala3(request.quantidadeContratada());
        BigDecimal preco =
                escala4(request.precoUnitario());
        BigDecimal valorTotal =
                dinheiro(quantidade.multiply(preco));

        String id = UUID.randomUUID().toString();
        int versao = request.versao() == null ? 1 : request.versao();
        String status = normalizarStatus(request.status());
        String fonte = primeiroNaoVazio(request.fonte(), "MANUAL");

        jdbcTemplate.update(
                """
                INSERT INTO item_contratual (
                    id,
                    obra_id,
                    contrato,
                    codigo_item,
                    descricao,
                    unidade_medida,
                    quantidade_contratada,
                    preco_unitario,
                    valor_total,
                    vigencia_inicio,
                    vigencia_fim,
                    versao,
                    status,
                    fonte
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                obra.getId(),
                request.contrato().trim(),
                request.codigoItem().trim(),
                request.descricao().trim(),
                request.unidadeMedida().trim(),
                quantidade,
                preco,
                valorTotal,
                request.vigenciaInicio(),
                request.vigenciaFim(),
                versao,
                status,
                fonte
        );

        memoryService.registrarObjeto(
                "ITEM_CONTRATUAL",
                id,
                request.codigoItem().trim(),
                request.descricao().trim(),
                status,
                FONTE
        );
        memoryService.registrarRelacaoAtiva(
                "ITEM_CONTRATUAL",
                id,
                "OBRA",
                obra.getId(),
                "PERTENCE_A",
                FONTE,
                "Item contratual pertence à obra."
        );

        return buscarPorId(id);
    }

    public List<ItemContratualResponse> listar(String obraIdentifier) {
        Obra obra = localizarObra(obraIdentifier);

        return jdbcTemplate.query(
                """
                SELECT *
                FROM item_contratual
                WHERE obra_id = ?
                ORDER BY contrato, codigo_item, versao DESC
                """,
                (rs, rowNumber) -> new ItemContratualResponse(
                        rs.getString("id"),
                        rs.getString("obra_id"),
                        rs.getString("contrato"),
                        rs.getString("codigo_item"),
                        rs.getString("descricao"),
                        rs.getString("unidade_medida"),
                        rs.getBigDecimal("quantidade_contratada"),
                        rs.getBigDecimal("preco_unitario"),
                        rs.getBigDecimal("valor_total"),
                        toLocalDate(rs.getDate("vigencia_inicio")),
                        toLocalDate(rs.getDate("vigencia_fim")),
                        rs.getInt("versao"),
                        rs.getString("status"),
                        rs.getString("fonte"),
                        toLocalDateTime(rs.getTimestamp("criado_em")),
                        toLocalDateTime(rs.getTimestamp("atualizado_em"))
                ),
                obra.getId()
        );
    }

    private ItemContratualResponse buscarPorId(String id) {
        return jdbcTemplate.queryForObject(
                """
                SELECT *
                FROM item_contratual
                WHERE id = ?
                """,
                (rs, rowNumber) -> new ItemContratualResponse(
                        rs.getString("id"),
                        rs.getString("obra_id"),
                        rs.getString("contrato"),
                        rs.getString("codigo_item"),
                        rs.getString("descricao"),
                        rs.getString("unidade_medida"),
                        rs.getBigDecimal("quantidade_contratada"),
                        rs.getBigDecimal("preco_unitario"),
                        rs.getBigDecimal("valor_total"),
                        toLocalDate(rs.getDate("vigencia_inicio")),
                        toLocalDate(rs.getDate("vigencia_fim")),
                        rs.getInt("versao"),
                        rs.getString("status"),
                        rs.getString("fonte"),
                        toLocalDateTime(rs.getTimestamp("criado_em")),
                        toLocalDateTime(rs.getTimestamp("atualizado_em"))
                ),
                id
        );
    }

    private Obra localizarObra(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Identificador da obra é obrigatório."
            );
        }

        List<Obra> obras =
                obraRepository.findByIdentificador(identifier.trim());

        if (obras.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Obra não encontrada: " + identifier
            );
        }

        if (obras.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Identificador de obra ambíguo: " + identifier
            );
        }

        return obras.getFirst();
    }

    private void validar(ItemContratualRequest request) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados do item contratual são obrigatórios."
            );
        }
        if (isBlank(request.contrato())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contrato é obrigatório.");
        }
        if (isBlank(request.codigoItem())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "codigoItem é obrigatório.");
        }
        if (isBlank(request.descricao())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "descricao é obrigatória.");
        }
        if (isBlank(request.unidadeMedida())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unidadeMedida é obrigatória.");
        }
        if (request.quantidadeContratada() == null
                || request.quantidadeContratada().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "quantidadeContratada deve ser maior ou igual a zero."
            );
        }
        if (request.precoUnitario() == null
                || request.precoUnitario().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "precoUnitario deve ser maior ou igual a zero."
            );
        }
        if (request.vigenciaInicio() != null
                && request.vigenciaFim() != null
                && request.vigenciaFim().isBefore(request.vigenciaInicio())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "vigenciaFim não pode ser anterior à vigenciaInicio."
            );
        }
    }

    private String normalizarStatus(String value) {
        String status = primeiroNaoVazio(value, "ATIVO")
                .toUpperCase(Locale.ROOT);

        if (!List.of("ATIVO", "INATIVO", "SUBSTITUIDO", "CANCELADO")
                .contains(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "status inválido para item contratual."
            );
        }

        return status;
    }

    private String primeiroNaoVazio(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BigDecimal escala3(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal escala4(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal dinheiro(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
