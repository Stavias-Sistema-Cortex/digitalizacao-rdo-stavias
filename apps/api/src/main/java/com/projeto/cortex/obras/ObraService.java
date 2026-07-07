package com.projeto.cortex.obras;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
public class ObraService {

    private final ObraRepository obraRepository;
    private final CortexOperationalMemoryService memoryService;

    public ObraService(ObraRepository obraRepository, CortexOperationalMemoryService memoryService) {
        this.obraRepository = obraRepository;
        this.memoryService = memoryService;
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

    @Transactional
    public ObraResponse criarObra(ObraRequest request) {
        String codigoContrato = normalizarObrigatorio(request.codigoContrato(), "codigoContrato");
        String nome = normalizarObrigatorio(request.nome(), "nome");

        if (obraRepository.existsByCodigoContrato(codigoContrato)) {
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
                codigoContrato,
                extrairCodigoCw(codigoContrato),
                normalizarOpcional(request.codigoInterno()),
                nome,
                normalizarOpcional(request.cliente()),
                normalizarOpcional(request.descricao()),
                normalizarOpcional(request.cidade()),
                normalizarUf(request.uf()),
                normalizarOpcional(request.rodovia()),
                status,
                fonteCriacao,
                normalizarOpcional(request.fonteArquivo()),
                normalizarOpcional(request.observacoes())
        );

        Obra salva = obraRepository.save(obra);

        memoryService.registrarEvento(
                ObraSyncEvento.TIPO_ENTIDADE,
                salva.getId(),
                ObraSyncEvento.TIPO_EVENTO,
                "OBRAS",
                ObraSyncEvento.payload(salva)
        );

        return ObraResponse.from(salva);
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
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Campo obrigatório ausente: " + fieldName
            );
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
}
