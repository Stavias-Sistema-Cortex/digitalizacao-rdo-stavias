package com.projeto.cortex.obras;

import com.projeto.cortex.financeiro.unit.FinancialUnitService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ObraService {

    private final ObraRepository obraRepository;
    private final CortexOperationalMemoryService memoryService;
    private final FinancialUnitService financialUnitService;

    public ObraService(
            ObraRepository obraRepository,
            CortexOperationalMemoryService memoryService,
            FinancialUnitService financialUnitService
    ) {
        this.obraRepository = obraRepository;
        this.memoryService = memoryService;
        this.financialUnitService = financialUnitService;
    }

    public List<ObraResponse> listarObras(String query) {
        List<Obra> obras;

        if (isBlank(query)) {
            obras = obraRepository.listar(PageRequest.of(0, 100));
        } else {
            obras = obraRepository.buscar(query.trim(), PageRequest.of(0, 100));
        }

        return obras.stream()
                .map(ObraResponse::from)
                .toList();
    }

    public List<ObraResponse> listarObrasArquivadas(
            String query,
            String actorId
    ) {
        normalizarObrigatorio(actorId, "actorId");
        List<Obra> obras;

        if (isBlank(query)) {
            obras = obraRepository.listarArquivadas(PageRequest.of(0, 100));
        } else {
            obras = obraRepository.buscarArquivadas(
                    query.trim(),
                    PageRequest.of(0, 100)
            );
        }

        return obras.stream()
                .map(ObraResponse::from)
                .toList();
    }

    @Transactional
    public ObraResponse criarObra(ObraRequest request, String actorId) {
        CadastroNormalizado cadastro = normalizarCadastro(request);

        if (obraRepository.existsByCodigoContrato(cadastro.codigoContrato())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Já existe uma obra com esse código de contrato."
            );
        }

        String status = isBlank(request.status())
                ? "ATIVA"
                : request.status().trim().toUpperCase(Locale.ROOT);

        String fonteCriacao = isBlank(request.fonteCriacao())
                ? "MANUAL"
                : request.fonteCriacao().trim().toUpperCase(Locale.ROOT);

        Obra obra = Obra.criar(
                cadastro.codigoContrato(),
                extrairCodigoCw(cadastro.codigoContrato()),
                cadastro.codigoInterno(),
                cadastro.nome(),
                cadastro.cliente(),
                cadastro.descricao(),
                cadastro.cidade(),
                cadastro.uf(),
                cadastro.rodovia(),
                status,
                fonteCriacao,
                cadastro.fonteArquivo(),
                cadastro.observacoes()
        );

        Obra salva = obraRepository.saveAndFlush(obra);

        ObraSyncEvento.registrarCriacao(memoryService, salva, actorId);
        financialUnitService.ensureWorksiteUnit(salva.getId(), actorId);

        return ObraResponse.from(salva);
    }

    @Transactional
    public ObraResponse atualizarObra(
            String obraId,
            ObraUpdateRequest request,
            String actorId
    ) {
        if (request == null) {
            throw badRequest("Corpo da atualização é obrigatório.");
        }
        String ator = normalizarObrigatorio(actorId, "actorId");
        Obra obra = requireObraParaMutacao(obraId);
        requireMatchingVersion(obra, request.baseVersion());
        CadastroNormalizado cadastro = normalizarCadastro(request);
        if (obraRepository.existsByCodigoContratoAndIdNot(
                cadastro.codigoContrato(),
                obra.getId()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Já existe uma obra com esse código de contrato."
            );
        }

        Map<String, Object> estadoAnterior = ObraSyncEvento.payload(obra);
        executarTransicao(() -> obra.editar(
                cadastro.codigoContrato(),
                extrairCodigoCw(cadastro.codigoContrato()),
                cadastro.codigoInterno(),
                cadastro.nome(),
                cadastro.cliente(),
                cadastro.descricao(),
                cadastro.cidade(),
                cadastro.uf(),
                cadastro.rodovia(),
                cadastro.fonteArquivo(),
                cadastro.observacoes()
        ));
        return persistirMutacao(
                obra,
                ator,
                ObraSyncEvento.TIPO_EVENTO,
                estadoAnterior
        );
    }

    @Transactional
    public ObraResponse desativarObra(
            String obraId,
            ObraVersionRequest request,
            String actorId
    ) {
        String ator = normalizarObrigatorio(actorId, "actorId");
        Obra obra = requireObraParaMutacao(obraId);
        requireMatchingVersion(
                obra,
                request == null ? null : request.baseVersion()
        );
        Map<String, Object> estadoAnterior = ObraSyncEvento.payload(obra);
        executarTransicao(obra::desativar);
        return persistirMutacao(
                obra,
                ator,
                ObraSyncEvento.EVENTO_DESATIVADA,
                estadoAnterior
        );
    }

    @Transactional
    public ObraResponse ativarObra(
            String obraId,
            ObraVersionRequest request,
            String actorId
    ) {
        String ator = normalizarObrigatorio(actorId, "actorId");
        Obra obra = requireObraParaMutacao(obraId);
        requireMatchingVersion(
                obra,
                request == null ? null : request.baseVersion()
        );
        Map<String, Object> estadoAnterior = ObraSyncEvento.payload(obra);
        executarTransicao(obra::ativar);
        return persistirMutacao(
                obra,
                ator,
                ObraSyncEvento.EVENTO_ATIVADA,
                estadoAnterior
        );
    }

    @Transactional
    public ObraResponse arquivarObra(
            String obraId,
            ObraVersionRequest request,
            String actorId
    ) {
        String ator = normalizarObrigatorio(actorId, "actorId");
        Obra obra = requireObraParaMutacao(obraId);
        requireMatchingVersion(
                obra,
                request == null ? null : request.baseVersion()
        );
        Map<String, Object> estadoAnterior = ObraSyncEvento.payload(obra);
        executarTransicao(obra::arquivar);
        return persistirMutacao(
                obra,
                ator,
                ObraSyncEvento.EVENTO_ARQUIVADA,
                estadoAnterior
        );
    }

    @Transactional
    public ObraResponse restaurarObra(
            String obraId,
            ObraVersionRequest request,
            String actorId
    ) {
        String ator = normalizarObrigatorio(actorId, "actorId");
        Obra obra = requireObraParaMutacao(obraId);
        requireMatchingVersion(
                obra,
                request == null ? null : request.baseVersion()
        );
        Map<String, Object> estadoAnterior = ObraSyncEvento.payload(obra);
        executarTransicao(obra::restaurar);
        return persistirMutacao(
                obra,
                ator,
                ObraSyncEvento.EVENTO_RESTAURADA,
                estadoAnterior
        );
    }

    private ObraResponse persistirMutacao(
            Obra obra,
            String actorId,
            String tipoEvento,
            Map<String, Object> estadoAnterior
    ) {
        Obra salva = obraRepository.saveAndFlush(obra);
        ObraSyncEvento.registrarMutacao(
                memoryService,
                salva,
                actorId,
                tipoEvento,
                estadoAnterior
        );
        return ObraResponse.from(salva);
    }

    private Obra requireObraParaMutacao(String obraId) {
        if (isBlank(obraId)) {
            throw badRequest("obraId é obrigatório.");
        }
        return obraRepository.findByIdForUpdate(obraId.trim())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Obra não encontrada."
                ));
    }

    private void requireMatchingVersion(Obra obra, Long baseVersion) {
        if (baseVersion == null || baseVersion < 0) {
            throw badRequest(
                    "baseVersion é obrigatório e não pode ser negativo."
            );
        }
        if (obra.getVersaoLinha() != baseVersion) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A obra foi alterada por outra operação. "
                            + "Recarregue antes de continuar."
            );
        }
    }

    private void executarTransicao(Runnable transicao) {
        try {
            transicao.run();
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    error.getMessage(),
                    error
            );
        }
    }

    private CadastroNormalizado normalizarCadastro(
            ObraCadastroRequest request
    ) {
        if (request == null) {
            throw badRequest("Dados cadastrais da obra são obrigatórios.");
        }
        return new CadastroNormalizado(
                normalizarObrigatorio(
                        request.codigoContrato(),
                        "codigoContrato"
                ),
                normalizarOpcional(request.codigoInterno()),
                normalizarObrigatorio(request.nome(), "nome"),
                normalizarOpcional(request.cliente()),
                normalizarOpcional(request.descricao()),
                normalizarOpcional(request.cidade()),
                normalizarUf(request.uf()),
                normalizarOpcional(request.rodovia()),
                normalizarOpcional(request.fonteArquivo()),
                normalizarOpcional(request.observacoes())
        );
    }

    private String extrairCodigoCw(String codigoContrato) {
        String normalizado = codigoContrato
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "")
                .toUpperCase(Locale.ROOT);

        if (!normalizado.startsWith("CW")) {
            return null;
        }

        return normalizado;
    }

    private String normalizarObrigatorio(String value, String fieldName) {
        if (isBlank(value)) {
            throw badRequest("Campo obrigatório ausente: " + fieldName);
        }

        return value.trim();
    }

    private String normalizarOpcional(String value) {
        if (isBlank(value)) {
            return null;
        }

        return value.trim();
    }

    private String normalizarUf(String uf) {
        if (isBlank(uf)) {
            return null;
        }

        String normalizada = uf.trim().toUpperCase(Locale.ROOT);

        if (normalizada.length() != 2) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "UF deve ter exatamente 2 caracteres."
            );
        }

        return normalizada;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record CadastroNormalizado(
            String codigoContrato,
            String codigoInterno,
            String nome,
            String cliente,
            String descricao,
            String cidade,
            String uf,
            String rodovia,
            String fonteArquivo,
            String observacoes
    ) {
    }
}
