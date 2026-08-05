package com.projeto.cortex.equipamentos;

import com.projeto.cortex.auth.CurrentUserService;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cadastro da máquina de terceiro dentro do Córtex.
 *
 * <p>A tabela {@code asset} é espelho de importação da Zeladoria, e a máquina
 * alugada não existe lá. Até aqui ela só entrava no RDO como texto solto: cada
 * apontador escrevia o prefixo do seu jeito e a mesma retroescavadeira virava
 * três equipamentos diferentes na medição do mês.
 *
 * <p>O cadastro dá identidade estável a ela. A linha de {@code asset} nasce com
 * {@code source_database = 'cortex'} e {@code source_pk} igual ao id do
 * cadastro — fora do alcance do conector, que só reescreve o que ele mesmo
 * importa da Zeladoria — e a partir daí a máquina atravessa o
 * caminho que já existia: elegibilidade por obra, catálogo do RDO, lançamento.
 * Nada disso precisa saber que ela não veio da importação.
 *
 * <p>Quem cadastra é quem alcança a obra, não só o Alfa. Quem aluga a máquina é
 * quem está na frente de serviço; exigir papel administrativo devolveria o
 * problema ao ponto de partida, com o apontador digitando prefixo à mão.
 */
@Service
public class EquipamentoTerceirizadoService {

    /**
     * O par que mantém estas linhas fora do alcance do conector da Zeladoria,
     * que só reescreve as linhas do próprio par de origem dele.
     */
    static final String SOURCE_DATABASE = "cortex";
    static final String SOURCE_TABLE = "equipamento_terceirizado";

    private static final String ORIGEM_ELEGIBILIDADE = "CADASTRO_TERCEIRIZADO";

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public EquipamentoTerceirizadoService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    public List<EquipamentoTerceirizadoResponse> listarPorObra(String obraId) {
        return jdbcTemplate.query(
                selecao() + """
                JOIN asset_obra_eligibilidade eligibility
                  ON eligibility.asset_id = cadastro.asset_id
                WHERE eligibility.obra_id = ?
                  AND eligibility.status = 'ATIVO'
                  AND asset.deleted_at IS NULL
                ORDER BY asset.name, asset.external_code, cadastro.id
                """,
                mapeador(),
                requireId(obraId, "obraId")
        );
    }

    @Transactional
    public EquipamentoTerceirizadoResponse criar(
            String obraId,
            EquipamentoTerceirizadoRequest request
    ) {
        String obra = requireId(obraId, "obraId");
        String actorId = currentUserService.requireUserId();
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados do equipamento terceirizado são obrigatórios."
            );
        }

        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString()
                : request.id().trim();

        /*
         * Reenvio do mesmo cadastro devolve o que já existe em vez de recusar.
         * A tela é usada em obra, com rede que cai no meio do envio; recusar o
         * repeteco faria a pessoa cadastrar a mesma máquina de novo com outro
         * id, que é exatamente a duplicidade que este cadastro existe para
         * evitar.
         */
        List<EquipamentoTerceirizadoResponse> existente = jdbcTemplate.query(
                selecao() + " WHERE cadastro.id = ?",
                mapeador(),
                id
        );
        if (!existente.isEmpty()) {
            garantirElegibilidade(existente.getFirst().assetId(), obra);
            return existente.getFirst();
        }

        String nome = requireText(request.nome(), "nome", 255);
        String fornecedor = requireText(request.fornecedor(), "fornecedor", 255);
        String prefixo = trimToNull(request.prefixo(), "prefixo", 120);
        String categoria = trimToNull(request.categoria(), "categoria", 120);
        String documento = trimToNull(
                request.documentoFornecedor(), "documentoFornecedor", 32
        );
        String observacoes = trimToNull(request.observacoes(), "observacoes", 1000);
        boolean ativo = request.ativo() == null || request.ativo();

        String assetId = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO asset (
                        id, source_database, source_table, source_pk,
                        external_code, name, category, active
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    assetId,
                    SOURCE_DATABASE,
                    SOURCE_TABLE,
                    id,
                    prefixo,
                    nome,
                    categoria,
                    ativo
            );
        } catch (DuplicateKeyException conflito) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Já existe um equipamento terceirizado com este identificador.",
                    conflito
            );
        }

        jdbcTemplate.update(
                """
                INSERT INTO equipamento_terceirizado (
                    id, asset_id, fornecedor, documento_fornecedor, observacoes,
                    criado_por, atualizado_por, versao_linha
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                """,
                id,
                assetId,
                fornecedor,
                documento,
                observacoes,
                actorId,
                actorId
        );

        garantirElegibilidade(assetId, obra);
        return requireCadastro(id);
    }

    @Transactional
    public EquipamentoTerceirizadoResponse atualizar(
            String obraId,
            String equipamentoId,
            EquipamentoTerceirizadoRequest request
    ) {
        String obra = requireId(obraId, "obraId");
        String actorId = currentUserService.requireUserId();
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados do equipamento terceirizado são obrigatórios."
            );
        }

        EquipamentoTerceirizadoResponse antes =
                requireCadastro(requireId(equipamentoId, "equipamentoId"));
        exigirCadastroDaObra(antes.assetId(), obra);

        long baseVersao = requireBaseVersion(request.baseVersao());
        if (antes.versaoEntidade() != baseVersao) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Conflito de versão do equipamento terceirizado."
            );
        }

        String nome = requireText(request.nome(), "nome", 255);
        String fornecedor = requireText(request.fornecedor(), "fornecedor", 255);
        boolean ativo = request.ativo() == null ? antes.ativo() : request.ativo();

        int atualizadas = jdbcTemplate.update(
                """
                UPDATE equipamento_terceirizado
                SET fornecedor = ?, documento_fornecedor = ?, observacoes = ?,
                    atualizado_por = ?, versao_linha = versao_linha + 1
                WHERE id = ? AND versao_linha = ?
                """,
                fornecedor,
                trimToNull(request.documentoFornecedor(), "documentoFornecedor", 32),
                trimToNull(request.observacoes(), "observacoes", 1000),
                actorId,
                antes.id(),
                baseVersao
        );
        if (atualizadas != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Conflito de versão do equipamento terceirizado."
            );
        }

        /*
         * Estado de vida mora só em `asset`. Guardar `ativo` também no cadastro
         * daria duas respostas para a mesma pergunta, e a divergência apareceria
         * como máquina sumindo do RDO sem ter sido desativada em lugar nenhum.
         */
        jdbcTemplate.update(
                """
                UPDATE asset
                SET external_code = ?, name = ?, category = ?, active = ?
                WHERE id = ?
                """,
                trimToNull(request.prefixo(), "prefixo", 120),
                nome,
                trimToNull(request.categoria(), "categoria", 120),
                ativo,
                antes.assetId()
        );

        return requireCadastro(antes.id());
    }

    /**
     * A máquina alugada volta em obra seguinte sem cadastro novo: a identidade
     * é a mesma, o que muda é o alcance.
     */
    private void garantirElegibilidade(String assetId, String obraId) {
        jdbcTemplate.update(
                """
                INSERT INTO asset_obra_eligibilidade (
                    asset_id, obra_id, status, origem
                ) VALUES (?, ?, 'ATIVO', ?)
                ON CONFLICT (asset_id, obra_id) DO UPDATE
                SET status = 'ATIVO', atualizado_em = now()
                """,
                assetId,
                obraId,
                ORIGEM_ELEGIBILIDADE
        );
    }

    private void exigirCadastroDaObra(String assetId, String obraId) {
        Boolean alcanca = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM asset_obra_eligibilidade
                    WHERE asset_id = ? AND obra_id = ? AND status = 'ATIVO'
                )
                """,
                Boolean.class,
                assetId,
                obraId
        );
        if (!Boolean.TRUE.equals(alcanca)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Equipamento terceirizado não pertence a esta obra."
            );
        }
    }

    private EquipamentoTerceirizadoResponse requireCadastro(String id) {
        List<EquipamentoTerceirizadoResponse> encontrados = jdbcTemplate.query(
                selecao() + " WHERE cadastro.id = ?",
                mapeador(),
                id
        );
        if (encontrados.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Equipamento terceirizado não encontrado."
            );
        }
        return encontrados.getFirst();
    }

    private String selecao() {
        return """
                SELECT cadastro.id,
                       cadastro.asset_id,
                       asset.external_code,
                       asset.name,
                       asset.category,
                       asset.active,
                       cadastro.fornecedor,
                       cadastro.documento_fornecedor,
                       cadastro.observacoes,
                       cadastro.versao_linha,
                       cadastro.criado_em,
                       cadastro.atualizado_em
                FROM equipamento_terceirizado cadastro
                JOIN asset ON asset.id = cadastro.asset_id
                """;
    }

    private RowMapper<EquipamentoTerceirizadoResponse> mapeador() {
        return (rs, rowNum) -> new EquipamentoTerceirizadoResponse(
                rs.getString("id"),
                rs.getString("asset_id"),
                rs.getString("external_code"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("fornecedor"),
                rs.getString("documento_fornecedor"),
                rs.getString("observacoes"),
                rs.getBoolean("active"),
                rs.getLong("versao_linha"),
                rs.getTimestamp("criado_em").toLocalDateTime(),
                rs.getTimestamp("atualizado_em").toLocalDateTime()
        );
    }

    private long requireBaseVersion(Long baseVersao) {
        if (baseVersao == null || baseVersao < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "baseVersao é obrigatória para atualizar o equipamento."
            );
        }
        return baseVersao;
    }

    private String requireId(String value, String campo) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    campo + " é obrigatório."
            );
        }
        return value.trim();
    }

    private String requireText(String value, String campo, int limite) {
        String texto = value == null ? "" : value.trim();
        if (texto.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    campo + " é obrigatório."
            );
        }
        if (texto.length() > limite) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    campo + " excede " + limite + " caracteres."
            );
        }
        return texto;
    }

    private String trimToNull(String value, String campo, int limite) {
        String texto = value == null ? "" : value.trim();
        if (texto.isEmpty()) {
            return null;
        }
        if (texto.length() > limite) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    campo + " excede " + limite + " caracteres."
            );
        }
        return texto;
    }
}
