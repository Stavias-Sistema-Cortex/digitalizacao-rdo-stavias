package com.projeto.cortex.intelligence.stavia.context;

import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StaviaContextResolutionService {

    private static final int MAX_ROWS = 1_000;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    );

    private static final Pattern RDO_NUMBER_PATTERN = Pattern.compile(
            "(?iu)\\brdo\\s*(?:n[ºo.]*)?\\s*([a-z0-9/-]+)"
    );

    private static final Set<String> STOPWORDS = Set.of(
            "quem",
            "qual",
            "quais",
            "obra",
            "rdo",
            "dessa",
            "desse",
            "nesse",
            "nesta",
            "neste",
            "aqui",
            "colaboradores",
            "equipamentos",
            "materiais",
            "turno",
            "cidade",
            "contrato",
            "foram",
            "esta",
            "estao"
    );

    private final JdbcTemplate jdbcTemplate;

    public StaviaContextResolutionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StaviaContextResolution resolve(StaviaQuestion question) {
        if (question == null || question.text() == null) {
            return StaviaContextResolution.notFound();
        }

        String rawQuestion = question.text();
        String normalizedQuestion = normalize(rawQuestion);

        StaviaContextResolution direct = resolveDirect(rawQuestion);
        if (direct.found() || direct.ambiguous()) {
            return direct;
        }

        List<String> tokens = tokens(normalizedQuestion);
        if (tokens.isEmpty()) {
            return StaviaContextResolution.notFound();
        }

        List<Candidate> candidates = new ArrayList<>();
        candidates.addAll(scoreObras(normalizedQuestion, tokens));
        candidates.addAll(scoreRdos(normalizedQuestion, tokens));

        if (candidates.isEmpty()) {
            return StaviaContextResolution.notFound();
        }

        List<Candidate> ordered = candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::score).reversed())
                .toList();

        Candidate best = ordered.getFirst();
        if (best.score() < 4) {
            return StaviaContextResolution.notFound();
        }

        List<Candidate> close = ordered.stream()
                .filter(candidate -> candidate.score() >= best.score() - 1)
                .toList();

        Set<String> closeWorksites = new LinkedHashSet<>();
        for (Candidate candidate : close) {
            closeWorksites.add(candidate.obraId());
        }

        if (closeWorksites.size() > 1) {
            return StaviaContextResolution.ambiguous(
                    close.stream()
                            .limit(4)
                            .map(Candidate::label)
                            .toList()
            );
        }

        return StaviaContextResolution.resolved(
                best.obraId(),
                best.label()
        );
    }

    private StaviaContextResolution resolveDirect(String question) {
        Matcher uuidMatcher = UUID_PATTERN.matcher(question);
        while (uuidMatcher.find()) {
            String id = uuidMatcher.group();

            StaviaContextResolution byObra = obraById(id);
            if (byObra.found()) {
                return byObra;
            }

            StaviaContextResolution byRdo = rdoById(id);
            if (byRdo.found()) {
                return byRdo;
            }
        }

        Matcher rdoMatcher = RDO_NUMBER_PATTERN.matcher(question);
        if (rdoMatcher.find()) {
            return rdoByNumber(rdoMatcher.group(1));
        }

        return StaviaContextResolution.notFound();
    }

    private StaviaContextResolution obraById(String id) {
        List<StaviaContextResolution> matches = jdbcTemplate.query(
                """
                SELECT id, COALESCE(NULLIF(nome, ''), NULLIF(codigo_contrato, ''), id) AS label
                FROM obra
                WHERE id = ?
                  AND arquivado_em IS NULL
                LIMIT 1
                """,
                (rs, rowNum) -> StaviaContextResolution.resolved(
                        rs.getString("id"),
                        rs.getString("label")
                ),
                id
        );

        return matches.stream().findFirst()
                .orElseGet(StaviaContextResolution::notFound);
    }

    private StaviaContextResolution rdoById(String id) {
        List<StaviaContextResolution> matches = jdbcTemplate.query(
                """
                SELECT
                    r.obra_id,
                    CONCAT('RDO ', COALESCE(NULLIF(r.numero_rdo, ''), r.id)) AS label
                FROM rdo r
                WHERE r.id = ?
                  AND r.cancelado_em IS NULL
                LIMIT 1
                """,
                (rs, rowNum) -> StaviaContextResolution.resolved(
                        rs.getString("obra_id"),
                        rs.getString("label")
                ),
                id
        );

        return matches.stream().findFirst()
                .orElseGet(StaviaContextResolution::notFound);
    }

    private StaviaContextResolution rdoByNumber(String value) {
        if (value == null || value.isBlank()) {
            return StaviaContextResolution.notFound();
        }

        List<Candidate> matches = jdbcTemplate.query(
                """
                SELECT
                    r.obra_id,
                    r.numero_rdo,
                    r.cidade,
                    r.data_rdo,
                    o.nome
                FROM rdo r
                JOIN obra o ON o.id = r.obra_id
                WHERE LOWER(r.numero_rdo) = LOWER(?)
                  AND r.cancelado_em IS NULL
                  AND o.arquivado_em IS NULL
                ORDER BY r.data_rdo DESC
                LIMIT 5
                """,
                (rs, rowNum) -> new Candidate(
                        rs.getString("obra_id"),
                        "RDO "
                                + StaviaText.fallback(
                                        rs.getString("numero_rdo"),
                                        rs.getString("nome")
                                )
                                + (
                                    rs.getString("cidade") == null
                                            ? ""
                                            : " · " + rs.getString("cidade")
                                ),
                        10
                ),
                value.trim()
        );

        return singleOrAmbiguous(matches);
    }

    private StaviaContextResolution singleOrAmbiguous(
            List<Candidate> matches
    ) {
        if (matches.isEmpty()) {
            return StaviaContextResolution.notFound();
        }

        Set<String> obras = new LinkedHashSet<>();
        for (Candidate match : matches) {
            obras.add(match.obraId());
        }

        if (obras.size() == 1) {
            Candidate first = matches.getFirst();
            return StaviaContextResolution.resolved(
                    first.obraId(),
                    first.label()
            );
        }

        return StaviaContextResolution.ambiguous(
                matches.stream()
                        .map(Candidate::label)
                        .limit(4)
                        .toList()
        );
    }

    private List<ObraCandidate> obras() {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    codigo_contrato,
                    codigo_cw,
                    codigo_interno,
                    nome,
                    cidade,
                    rodovia
                FROM obra
                WHERE arquivado_em IS NULL
                ORDER BY atualizado_em DESC, id
                LIMIT ?
                """,
                (rs, rowNum) -> new ObraCandidate(
                        rs.getString("id"),
                        rs.getString("codigo_contrato"),
                        rs.getString("codigo_cw"),
                        rs.getString("codigo_interno"),
                        rs.getString("nome"),
                        rs.getString("cidade"),
                        rs.getString("rodovia")
                ),
                MAX_ROWS
        );
    }

    private List<RdoCandidate> rdos() {
        return jdbcTemplate.query(
                """
                SELECT
                    r.obra_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.cidade,
                    r.contrato,
                    r.rodovia,
                    o.nome,
                    o.codigo_contrato,
                    o.codigo_cw
                FROM rdo r
                JOIN obra o ON o.id = r.obra_id
                WHERE r.cancelado_em IS NULL
                  AND o.arquivado_em IS NULL
                ORDER BY r.data_rdo DESC, r.atualizado_em DESC, r.id
                LIMIT ?
                """,
                (rs, rowNum) -> new RdoCandidate(
                        rs.getString("obra_id"),
                        rs.getString("numero_rdo"),
                        String.valueOf(rs.getDate("data_rdo")),
                        rs.getString("cidade"),
                        rs.getString("contrato"),
                        rs.getString("rodovia"),
                        rs.getString("nome"),
                        rs.getString("codigo_contrato"),
                        rs.getString("codigo_cw")
                ),
                MAX_ROWS
        );
    }

    private List<String> tokens(String normalizedQuestion) {
        return Arrays.stream(normalizedQuestion.split("\\s+"))
                .filter(token -> token.length() >= 3)
                .filter(token -> !STOPWORDS.contains(token))
                .distinct()
                .toList();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }

        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s/-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int fieldScore(
            String question,
            String value,
            int weight
    ) {
        String normalizedValue = normalize(value);
        if (normalizedValue.length() < 3) {
            return 0;
        }

        return question.contains(normalizedValue) ? weight : 0;
    }

    private int tokenScore(List<String> tokens, String... fields) {
        String haystack = normalize(String.join(
                " ",
                Arrays.stream(fields)
                        .filter(field -> field != null && !field.isBlank())
                        .toList()
        ));

        int score = 0;
        for (String token : tokens) {
            if (haystack.contains(token)) {
                score++;
            }
        }

        return score;
    }

    private record ObraCandidate(
            String id,
            String codigoContrato,
            String codigoCw,
            String codigoInterno,
            String nome,
            String cidade,
            String rodovia
    ) {
    }

    private record RdoCandidate(
            String obraId,
            String numeroRdo,
            String dataRdo,
            String cidade,
            String contrato,
            String rodovia,
            String obraNome,
            String codigoContrato,
            String codigoCw
    ) {
    }

    private record Candidate(
            String obraId,
            String label,
            int score
    ) {
    }

    private List<Candidate> scoreObras(
            String question,
            List<String> tokens
    ) {
        return obras().stream()
                .map(obra -> scoreObraCandidate(obra, question, tokens))
                .filter(candidate -> candidate.score() > 0)
                .toList();
    }

    private List<Candidate> scoreRdos(
            String question,
            List<String> tokens
    ) {
        return rdos().stream()
                .map(rdo -> scoreRdoCandidate(rdo, question, tokens))
                .filter(candidate -> candidate.score() > 0)
                .toList();
    }

    private Candidate scoreObraCandidate(
            ObraCandidate obra,
            String question,
            List<String> tokens
    ) {
        int score = fieldScore(question, obra.id(), 12)
                + fieldScore(question, obra.codigoCw(), 9)
                + fieldScore(question, obra.codigoContrato(), 8)
                + fieldScore(question, obra.codigoInterno(), 8)
                + fieldScore(question, obra.nome(), 6)
                + fieldScore(question, obra.cidade(), 5)
                + fieldScore(question, obra.rodovia(), 4)
                + tokenScore(
                        tokens,
                        obra.codigoContrato(),
                        obra.codigoCw(),
                        obra.codigoInterno(),
                        obra.nome(),
                        obra.cidade(),
                        obra.rodovia()
                );

        return new Candidate(
                obra.id(),
                StaviaText.fallback(
                        obra.nome(),
                        StaviaText.fallback(
                                obra.codigoContrato(),
                                obra.id()
                        )
                ),
                score
        );
    }

    private Candidate scoreRdoCandidate(
            RdoCandidate rdo,
            String question,
            List<String> tokens
    ) {
        int score = fieldScore(question, rdo.numeroRdo(), 9)
                + fieldScore(question, rdo.dataRdo(), 5)
                + fieldScore(question, rdo.cidade(), 5)
                + fieldScore(question, rdo.contrato(), 5)
                + fieldScore(question, rdo.rodovia(), 4)
                + fieldScore(question, rdo.codigoCw(), 7)
                + fieldScore(question, rdo.codigoContrato(), 6)
                + fieldScore(question, rdo.obraNome(), 5)
                + tokenScore(
                        tokens,
                        rdo.numeroRdo(),
                        rdo.dataRdo(),
                        rdo.cidade(),
                        rdo.contrato(),
                        rdo.rodovia(),
                        rdo.obraNome(),
                        rdo.codigoContrato(),
                        rdo.codigoCw()
                );

        return new Candidate(
                rdo.obraId(),
                "RDO "
                        + StaviaText.fallback(
                                rdo.numeroRdo(),
                                rdo.dataRdo()
                        )
                        + (
                            rdo.cidade() == null || rdo.cidade().isBlank()
                                    ? ""
                                    : " · " + rdo.cidade()
                        ),
                score
        );
    }
}
