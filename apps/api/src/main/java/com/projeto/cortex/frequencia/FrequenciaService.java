package com.projeto.cortex.frequencia;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FrequenciaService {

    private static final String FONTE = "FREQUENCIA_API";

    private static final Set<String> PRESENCA_STATUS = Set.of(
            "REGISTRADA",
            "VALIDADA",
            "DIVERGENTE",
            "CANCELADA"
    );

    private static final Set<String> OCORRENCIA_TIPOS = Set.of(
            "PRESENTE",
            "FALTA",
            "FALTA_JUSTIFICADA",
            "ATRASO",
            "SAIDA_ANTECIPADA",
            "HORA_EXTRA",
            "FOLGA",
            "FERIAS",
            "ATESTADO",
            "AFASTAMENTO",
            "TREINAMENTO",
            "SEM_REGISTRO",
            "DIVERGENCIA"
    );

    private static final Set<String> OCORRENCIA_STATUS = Set.of(
            "REGISTRADA",
            "JUSTIFICADA",
            "VALIDADA",
            "CANCELADA"
    );

    private static final Set<String> AJUSTE_STATUS = Set.of(
            "PENDENTE",
            "APROVADO",
            "REJEITADO",
            "CANCELADO"
    );

    private final JdbcTemplate jdbcTemplate;
    private final CortexOperationalMemoryService memoryService;

    public FrequenciaService(
            JdbcTemplate jdbcTemplate,
            CortexOperationalMemoryService memoryService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryService = memoryService;
    }

    @Transactional
    public FrequenciaResumoResponse.JornadaResponse registrarJornada(
            String colaboradorId,
            JornadaPlanejadaRequest request
    ) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados da jornada são obrigatórios."
            );
        }
        ColaboradorDados colaborador =
                buscarColaboradorAtivo(colaboradorId);
        LocalDate data =
                requerData(request == null ? null : request.dataJornada(), "dataJornada");
        int minutos =
                minutosValidos(
                        request.minutosPrevistos(),
                        "minutosPrevistos"
                );
        String origem =
                primeiroNaoVazio(request.origem(), "MANUAL");
        String id = UUID.randomUUID().toString();

        jdbcTemplate.update(
                """
                INSERT INTO jornada_planejada (
                    id,
                    colaborador_id,
                    data_jornada,
                    minutos_previstos,
                    hora_inicio,
                    hora_fim,
                    origem
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    minutos_previstos = VALUES(minutos_previstos),
                    hora_inicio = VALUES(hora_inicio),
                    hora_fim = VALUES(hora_fim),
                    origem = VALUES(origem),
                    atualizado_em = CURRENT_TIMESTAMP(6)
                """,
                id,
                colaborador.id(),
                data,
                minutos,
                request.horaInicio(),
                request.horaFim(),
                origem
        );

        registrarEventoFrequencia(
                colaborador,
                "JORNADA_PLANEJADA_REGISTRADA",
                data,
                Map.of(
                        "minutosPrevistos",
                        minutos,
                        "origem",
                        origem
                )
        );

        recalcularSaldoBancoHoras(
                colaborador,
                inicioMes(data),
                fimMes(data)
        );

        return new FrequenciaResumoResponse.JornadaResponse(
                id,
                data,
                minutos,
                request.horaInicio(),
                request.horaFim(),
                origem
        );
    }

    @Transactional
    public FrequenciaResumoResponse.PresencaResponse registrarPresenca(
            String colaboradorId,
            RegistroPresencaRequest request
    ) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados da presença são obrigatórios."
            );
        }
        ColaboradorDados colaborador =
                buscarColaboradorAtivo(colaboradorId);
        LocalDate data =
                requerData(request == null ? null : request.dataPresenca(), "dataPresenca");
        int minutos =
                minutosValidos(
                        request.minutosRegistrados(),
                        "minutosRegistrados"
                );
        String status =
                normalizarEnum(
                        request.status(),
                        "REGISTRADA",
                        PRESENCA_STATUS,
                        "status"
                );
        String origem =
                primeiroNaoVazio(request.origem(), "OPERACIONAL");
        String id = UUID.randomUUID().toString();

        validarRdoOpcional(request.rdoId());

        jdbcTemplate.update(
                """
                INSERT INTO registro_presenca (
                    id,
                    colaborador_id,
                    data_presenca,
                    minutos_registrados,
                    origem,
                    status,
                    rdo_id,
                    observacoes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                colaborador.id(),
                data,
                minutos,
                origem,
                status,
                nuloSeVazio(request.rdoId()),
                nuloSeVazio(request.observacoes())
        );

        registrarEventoFrequencia(
                colaborador,
                "PRESENCA_OPERACIONAL_REGISTRADA",
                data,
                Map.of(
                        "presencaId",
                        id,
                        "minutosRegistrados",
                        minutos,
                        "status",
                        status,
                        "origem",
                        origem
                )
        );

        recalcularSaldoBancoHoras(
                colaborador,
                inicioMes(data),
                fimMes(data)
        );

        return new FrequenciaResumoResponse.PresencaResponse(
                id,
                data,
                minutos,
                origem,
                status,
                nuloSeVazio(request.rdoId()),
                nuloSeVazio(request.observacoes())
        );
    }

    @Transactional
    public FrequenciaResumoResponse.OcorrenciaResponse registrarOcorrencia(
            String colaboradorId,
            String usuarioAutenticadoId,
            OcorrenciaFrequenciaRequest request
    ) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados da ocorrência são obrigatórios."
            );
        }
        ColaboradorDados colaborador =
                buscarColaboradorAtivo(colaboradorId);
        LocalDate data =
                requerData(
                        request == null ? null : request.dataOcorrencia(),
                        "dataOcorrencia"
                );
        String tipo =
                normalizarEnum(
                        request.tipo(),
                        null,
                        OCORRENCIA_TIPOS,
                        "tipo"
                );
        String status =
                normalizarEnum(
                        request.status(),
                        "REGISTRADA",
                        OCORRENCIA_STATUS,
                        "status"
                );
        int minutos =
                minutosValidos(
                        request.minutos() == null ? 0 : request.minutos(),
                        "minutos"
                );
        String origem =
                primeiroNaoVazio(request.origem(), "OPERACIONAL");
        String id = UUID.randomUUID().toString();
        String criador = usuarioAutenticadoId == null || usuarioAutenticadoId.isBlank()
                ? null
                : usuarioAutenticadoId.trim();
        if (criador == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Usuário autenticado é obrigatório para registrar ocorrência."
            );
        }
        String validador = "VALIDADA".equals(status) ? criador : null;

        validarRdoOpcional(request.rdoId());

        jdbcTemplate.update(
                """
                INSERT INTO ocorrencia_frequencia (
                    id,
                    colaborador_id,
                    data_ocorrencia,
                    tipo,
                    minutos,
                    status,
                    origem,
                    rdo_id,
                    justificativa,
                    criado_por,
                    validado_por,
                    validado_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? IS NULL THEN NULL ELSE CURRENT_TIMESTAMP(6) END)
                """,
                id,
                colaborador.id(),
                data,
                tipo,
                minutos,
                status,
                origem,
                nuloSeVazio(request.rdoId()),
                nuloSeVazio(request.justificativa()),
                criador,
                validador,
                validador
        );

        registrarEventoFrequencia(
                colaborador,
                eventoOcorrencia(tipo, status),
                data,
                Map.of(
                        "ocorrenciaId",
                        id,
                        "tipo",
                        tipo,
                        "minutos",
                        minutos,
                        "status",
                        status,
                        "origem",
                        origem
                )
        );

        recalcularSaldoBancoHoras(
                colaborador,
                inicioMes(data),
                fimMes(data)
        );

        return new FrequenciaResumoResponse.OcorrenciaResponse(
                id,
                data,
                tipo,
                minutos,
                status,
                origem,
                nuloSeVazio(request.rdoId()),
                nuloSeVazio(request.justificativa())
        );
    }

    @Transactional
    public BancoHorasResumoResponse ajustarBancoHoras(
            String colaboradorId,
            String usuarioAutenticadoId,
            AjusteBancoHorasRequest request
    ) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados do ajuste são obrigatórios."
            );
        }
        ColaboradorDados colaborador =
                buscarColaboradorAtivo(colaboradorId);
        LocalDate data =
                requerData(request == null ? null : request.dataAjuste(), "dataAjuste");
        LocalDate periodoInicio =
                request.periodoInicio() == null
                        ? inicioMes(data)
                        : request.periodoInicio();
        LocalDate periodoFim =
                request.periodoFim() == null
                        ? fimMes(data)
                        : request.periodoFim();

        if (periodoFim.isBefore(periodoInicio)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "periodoFim não pode ser anterior a periodoInicio."
            );
        }

        if (request.minutosNovo() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "minutosNovo é obrigatório."
            );
        }

        if (request.motivo() == null || request.motivo().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "motivo é obrigatório para ajuste de banco de horas."
            );
        }

        if (usuarioAutenticadoId == null || usuarioAutenticadoId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Usuário autenticado é obrigatório para ajuste de banco de horas."
            );
        }

        String status =
                normalizarEnum(
                        request.status(),
                        "PENDENTE",
                        AJUSTE_STATUS,
                        "status"
                );

        BancoHorasResumoResponse saldoAntes =
                recalcularSaldoBancoHoras(
                        colaborador,
                        periodoInicio,
                        periodoFim
                );
        int minutosAnterior =
                saldoAntes.minutosSaldoAtual();
        int minutosNovo =
                request.minutosNovo();
        int delta =
                minutosNovo - minutosAnterior;
        String saldoId =
                buscarSaldoId(colaborador.id(), periodoInicio, periodoFim);
        String ajusteId =
                UUID.randomUUID().toString();

        jdbcTemplate.update(
                """
                INSERT INTO ajuste_banco_horas (
                    id,
                    colaborador_id,
                    saldo_id,
                    data_ajuste,
                    minutos_anterior,
                    minutos_novo,
                    minutos_delta,
                    motivo,
                    usuario_id,
                    status,
                    aprovado_por,
                    aprovado_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? IS NULL THEN NULL ELSE CURRENT_TIMESTAMP(6) END)
                """,
                ajusteId,
                colaborador.id(),
                saldoId,
                data,
                minutosAnterior,
                minutosNovo,
                delta,
                request.motivo().trim(),
                usuarioAutenticadoId.trim(),
                status,
                nuloSeVazio(request.aprovadoPor()),
                nuloSeVazio(request.aprovadoPor())
        );

        registrarEventoFrequencia(
                colaborador,
                "BANCO_HORAS_AJUSTADO",
                data,
                Map.of(
                        "ajusteId",
                        ajusteId,
                        "periodoInicio",
                        periodoInicio,
                        "periodoFim",
                        periodoFim,
                        "minutosAnterior",
                        minutosAnterior,
                        "minutosNovo",
                        minutosNovo,
                        "minutosDelta",
                        delta,
                        "status",
                        status,
                        "usuarioId",
                        usuarioAutenticadoId.trim()
                )
        );

        return recalcularSaldoBancoHoras(
                colaborador,
                periodoInicio,
                periodoFim
        );
    }

    public FrequenciaResumoResponse buscarResumo(
            String colaboradorId,
            LocalDate inicio,
            LocalDate fim
    ) {
        ColaboradorDados colaborador =
                buscarColaboradorAtivo(colaboradorId);
        LocalDate periodoInicio =
                inicio == null ? inicioMes(LocalDate.now()) : inicio;
        LocalDate periodoFim =
                fim == null ? fimMes(periodoInicio) : fim;

        if (periodoFim.isBefore(periodoInicio)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "fim não pode ser anterior a inicio."
            );
        }

        return new FrequenciaResumoResponse(
                colaborador.id(),
                colaborador.nome(),
                periodoInicio,
                periodoFim,
                listarJornadas(colaborador.id(), periodoInicio, periodoFim),
                listarPresencas(colaborador.id(), periodoInicio, periodoFim),
                listarOcorrencias(colaborador.id(), periodoInicio, periodoFim),
                buscarBancoHoras(colaborador.id(), periodoInicio, periodoFim)
        );
    }

    public BancoHorasResumoResponse buscarBancoHoras(
            String colaboradorId,
            LocalDate inicio,
            LocalDate fim
    ) {
        ColaboradorDados colaborador =
                buscarColaboradorAtivo(colaboradorId);
        LocalDate periodoInicio =
                inicio == null ? inicioMes(LocalDate.now()) : inicio;
        LocalDate periodoFim =
                fim == null ? fimMes(periodoInicio) : fim;

        return bancoHorasResponse(
                colaborador,
                periodoInicio,
                periodoFim,
                calcularBanco(colaborador.id(), periodoInicio, periodoFim)
        );
    }

    private BancoHorasResumoResponse recalcularSaldoBancoHoras(
            ColaboradorDados colaborador,
            LocalDate periodoInicio,
            LocalDate periodoFim
    ) {
        BancoHorasCalculo calculo =
                calcularBanco(colaborador.id(), periodoInicio, periodoFim);
        String saldoId =
                buscarSaldoId(colaborador.id(), periodoInicio, periodoFim);

        if (saldoId == null) {
            saldoId = UUID.randomUUID().toString();
        }

        jdbcTemplate.update(
                """
                INSERT INTO saldo_banco_horas (
                    id,
                    colaborador_id,
                    periodo_inicio,
                    periodo_fim,
                    minutos_saldo_anterior,
                    minutos_credito,
                    minutos_debito,
                    minutos_ajuste,
                    minutos_saldo_atual
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    minutos_saldo_anterior = VALUES(minutos_saldo_anterior),
                    minutos_credito = VALUES(minutos_credito),
                    minutos_debito = VALUES(minutos_debito),
                    minutos_ajuste = VALUES(minutos_ajuste),
                    minutos_saldo_atual = VALUES(minutos_saldo_atual),
                    calculado_em = CURRENT_TIMESTAMP(6)
                """,
                saldoId,
                colaborador.id(),
                periodoInicio,
                periodoFim,
                calculo.saldoAnterior(),
                calculo.credito(),
                calculo.debito(),
                calculo.ajuste(),
                calculo.saldoAtual()
        );

        return bancoHorasResponse(
                colaborador,
                periodoInicio,
                periodoFim,
                calculo
        );
    }

    private BancoHorasCalculo calcularBanco(
            String colaboradorId,
            LocalDate inicio,
            LocalDate fim
    ) {
        int saldoAnterior =
                saldoAnterior(colaboradorId, inicio);
        int previstos =
                somaInt(
                        """
                        SELECT SUM(minutos_previstos)
                        FROM jornada_planejada
                        WHERE colaborador_id = ?
                          AND data_jornada BETWEEN ? AND ?
                        """,
                        colaboradorId,
                        inicio,
                        fim
                );
        int registrados =
                somaInt(
                        """
                        SELECT SUM(minutos_registrados)
                        FROM registro_presenca
                        WHERE colaborador_id = ?
                          AND data_presenca BETWEEN ? AND ?
                          AND status <> 'CANCELADA'
                        """,
                        colaboradorId,
                        inicio,
                        fim
                );
        int extraOcorrencias =
                somaInt(
                        """
                        SELECT SUM(minutos)
                        FROM ocorrencia_frequencia
                        WHERE colaborador_id = ?
                          AND data_ocorrencia BETWEEN ? AND ?
                          AND tipo = 'HORA_EXTRA'
                          AND status <> 'CANCELADA'
                        """,
                        colaboradorId,
                        inicio,
                        fim
                );
        int debitoOcorrencias =
                somaInt(
                        """
                        SELECT SUM(minutos)
                        FROM ocorrencia_frequencia
                        WHERE colaborador_id = ?
                          AND data_ocorrencia BETWEEN ? AND ?
                          AND tipo IN ('FALTA', 'ATRASO', 'SAIDA_ANTECIPADA', 'SEM_REGISTRO')
                          AND status <> 'CANCELADA'
                        """,
                        colaboradorId,
                        inicio,
                        fim
                );
        int ajustes =
                somaInt(
                        """
                        SELECT SUM(minutos_delta)
                        FROM ajuste_banco_horas
                        WHERE colaborador_id = ?
                          AND data_ajuste BETWEEN ? AND ?
                          AND status = 'APROVADO'
                        """,
                        colaboradorId,
                        inicio,
                        fim
                );

        int credito =
                Math.max(0, registrados - previstos) + extraOcorrencias;
        int debito =
                Math.max(0, previstos - registrados) + debitoOcorrencias;
        int saldoAtual =
                saldoAnterior + credito - debito + ajustes;

        return new BancoHorasCalculo(
                saldoAnterior,
                credito,
                debito,
                ajustes,
                saldoAtual
        );
    }

    private BancoHorasResumoResponse bancoHorasResponse(
            ColaboradorDados colaborador,
            LocalDate inicio,
            LocalDate fim,
            BancoHorasCalculo calculo
    ) {
        return new BancoHorasResumoResponse(
                colaborador.id(),
                colaborador.nome(),
                inicio,
                fim,
                calculo.saldoAnterior(),
                calculo.credito(),
                calculo.debito(),
                calculo.ajuste(),
                calculo.saldoAtual(),
                listarAjustes(colaborador.id(), inicio, fim)
        );
    }

    private List<FrequenciaResumoResponse.JornadaResponse> listarJornadas(
            String colaboradorId,
            LocalDate inicio,
            LocalDate fim
    ) {
        return jdbcTemplate.query(
                """
                SELECT id, data_jornada, minutos_previstos, hora_inicio, hora_fim, origem
                FROM jornada_planejada
                WHERE colaborador_id = ?
                  AND data_jornada BETWEEN ? AND ?
                ORDER BY data_jornada
                """,
                (rs, rowNumber) -> new FrequenciaResumoResponse.JornadaResponse(
                        rs.getString("id"),
                        rs.getDate("data_jornada").toLocalDate(),
                        rs.getInt("minutos_previstos"),
                        toLocalTime(rs.getTime("hora_inicio")),
                        toLocalTime(rs.getTime("hora_fim")),
                        rs.getString("origem")
                ),
                colaboradorId,
                inicio,
                fim
        );
    }

    private List<FrequenciaResumoResponse.PresencaResponse> listarPresencas(
            String colaboradorId,
            LocalDate inicio,
            LocalDate fim
    ) {
        return jdbcTemplate.query(
                """
                SELECT id, data_presenca, minutos_registrados, origem, status, rdo_id, observacoes
                FROM registro_presenca
                WHERE colaborador_id = ?
                  AND data_presenca BETWEEN ? AND ?
                ORDER BY data_presenca, criado_em
                """,
                (rs, rowNumber) -> new FrequenciaResumoResponse.PresencaResponse(
                        rs.getString("id"),
                        rs.getDate("data_presenca").toLocalDate(),
                        rs.getInt("minutos_registrados"),
                        rs.getString("origem"),
                        rs.getString("status"),
                        rs.getString("rdo_id"),
                        rs.getString("observacoes")
                ),
                colaboradorId,
                inicio,
                fim
        );
    }

    private List<FrequenciaResumoResponse.OcorrenciaResponse> listarOcorrencias(
            String colaboradorId,
            LocalDate inicio,
            LocalDate fim
    ) {
        return jdbcTemplate.query(
                """
                SELECT id, data_ocorrencia, tipo, minutos, status, origem, rdo_id, justificativa
                FROM ocorrencia_frequencia
                WHERE colaborador_id = ?
                  AND data_ocorrencia BETWEEN ? AND ?
                ORDER BY data_ocorrencia, criado_em
                """,
                (rs, rowNumber) -> new FrequenciaResumoResponse.OcorrenciaResponse(
                        rs.getString("id"),
                        rs.getDate("data_ocorrencia").toLocalDate(),
                        rs.getString("tipo"),
                        rs.getInt("minutos"),
                        rs.getString("status"),
                        rs.getString("origem"),
                        rs.getString("rdo_id"),
                        rs.getString("justificativa")
                ),
                colaboradorId,
                inicio,
                fim
        );
    }

    private List<BancoHorasResumoResponse.AjusteResponse> listarAjustes(
            String colaboradorId,
            LocalDate inicio,
            LocalDate fim
    ) {
        return jdbcTemplate.query(
                """
                SELECT id, data_ajuste, minutos_anterior, minutos_novo,
                       minutos_delta, motivo, usuario_id, status, aprovado_por
                FROM ajuste_banco_horas
                WHERE colaborador_id = ?
                  AND data_ajuste BETWEEN ? AND ?
                ORDER BY data_ajuste, criado_em
                """,
                (rs, rowNumber) -> new BancoHorasResumoResponse.AjusteResponse(
                        rs.getString("id"),
                        rs.getDate("data_ajuste").toLocalDate(),
                        rs.getInt("minutos_anterior"),
                        rs.getInt("minutos_novo"),
                        rs.getInt("minutos_delta"),
                        rs.getString("motivo"),
                        rs.getString("usuario_id"),
                        rs.getString("status"),
                        rs.getString("aprovado_por")
                ),
                colaboradorId,
                inicio,
                fim
        );
    }

    private ColaboradorDados buscarColaboradorAtivo(String colaboradorId) {
        if (colaboradorId == null || colaboradorId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "colaboradorId é obrigatório."
            );
        }

        List<ColaboradorDados> colaboradores = jdbcTemplate.query(
                """
                SELECT id, nome, ativo
                FROM colaborador
                WHERE id = ?
                  AND deletado_em IS NULL
                """,
                (rs, rowNumber) -> new ColaboradorDados(
                        rs.getString("id"),
                        rs.getString("nome"),
                        rs.getBoolean("ativo")
                ),
                colaboradorId
        );

        if (colaboradores.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Colaborador não encontrado."
            );
        }

        ColaboradorDados colaborador =
                colaboradores.getFirst();
        if (!colaborador.ativo()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Colaborador inativo não pode receber lançamento de frequência."
            );
        }

        return colaborador;
    }

    private void validarRdoOpcional(String rdoId) {
        if (rdoId == null || rdoId.isBlank()) {
            return;
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rdo WHERE id = ? AND cancelado_em IS NULL",
                Integer.class,
                rdoId
        );

        if (count == null || count == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "RDO informado não existe ou está cancelado."
            );
        }
    }

    private int saldoAnterior(
            String colaboradorId,
            LocalDate inicio
    ) {
        return jdbcTemplate.query(
                """
                SELECT minutos_saldo_atual
                FROM saldo_banco_horas
                WHERE colaborador_id = ?
                  AND periodo_fim < ?
                ORDER BY periodo_fim DESC, calculado_em DESC
                LIMIT 1
                """,
                (rs, rowNumber) -> rs.getInt("minutos_saldo_atual"),
                colaboradorId,
                inicio
        ).stream().findFirst().orElse(0);
    }

    private String buscarSaldoId(
            String colaboradorId,
            LocalDate periodoInicio,
            LocalDate periodoFim
    ) {
        return jdbcTemplate.query(
                """
                SELECT id
                FROM saldo_banco_horas
                WHERE colaborador_id = ?
                  AND periodo_inicio = ?
                  AND periodo_fim = ?
                """,
                (rs, rowNumber) -> rs.getString("id"),
                colaboradorId,
                periodoInicio,
                periodoFim
        ).stream().findFirst().orElse(null);
    }

    private int somaInt(
            String sql,
            Object... args
    ) {
        Number value =
                jdbcTemplate.queryForObject(sql, Number.class, args);

        return value == null ? 0 : value.intValue();
    }

    private void registrarEventoFrequencia(
            ColaboradorDados colaborador,
            String tipoEvento,
            LocalDate data,
            Map<String, Object> dados
    ) {
        memoryService.registrarObjeto(
                "COLABORADOR",
                colaborador.id(),
                colaborador.id(),
                colaborador.nome(),
                "ATIVO",
                FONTE
        );

        Map<String, Object> payload =
                new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("colaboradorId", colaborador.id());
        payload.put("colaboradorNome", colaborador.nome());
        payload.put("data", data);
        payload.putAll(dados);

        memoryService.registrarEvento(
                "COLABORADOR",
                colaborador.id(),
                tipoEvento,
                FONTE,
                payload
        );
    }

    private String eventoOcorrencia(
            String tipo,
            String status
    ) {
        if ("FALTA_JUSTIFICADA".equals(tipo)
                || ("FALTA".equals(tipo) && "JUSTIFICADA".equals(status))) {
            return "FALTA_JUSTIFICADA";
        }
        if ("FALTA".equals(tipo)) {
            return "FALTA_REGISTRADA";
        }
        if ("HORA_EXTRA".equals(tipo)) {
            return "HORA_EXTRA_REGISTRADA";
        }
        return "OCORRENCIA_FREQUENCIA_REGISTRADA";
    }

    private LocalDate requerData(
            LocalDate data,
            String campo
    ) {
        if (data == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    campo + " é obrigatório."
            );
        }
        return data;
    }

    private int minutosValidos(
            Integer minutos,
            String campo
    ) {
        if (minutos == null || minutos < 0 || minutos > 1440) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    campo + " deve estar entre 0 e 1440."
            );
        }
        return minutos;
    }

    private String normalizarEnum(
            String value,
            String defaultValue,
            Set<String> allowed,
            String campo
    ) {
        String normalized =
                value == null || value.isBlank()
                        ? defaultValue
                        : value.trim().toUpperCase();

        if (normalized == null || !allowed.contains(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    campo + " inválido: " + value
            );
        }
        return normalized;
    }

    private LocalDate inicioMes(LocalDate data) {
        return data.withDayOfMonth(1);
    }

    private LocalDate fimMes(LocalDate data) {
        return data.withDayOfMonth(data.lengthOfMonth());
    }

    private LocalTime toLocalTime(Time time) {
        return time == null ? null : time.toLocalTime();
    }

    private String primeiroNaoVazio(
            String value,
            String fallback
    ) {
        return value == null || value.isBlank()
                ? fallback
                : value.trim();
    }

    private String nuloSeVazio(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }

    private record ColaboradorDados(
            String id,
            String nome,
            boolean ativo
    ) {
    }

    private record BancoHorasCalculo(
            int saldoAnterior,
            int credito,
            int debito,
            int ajuste,
            int saldoAtual
    ) {
    }
}
