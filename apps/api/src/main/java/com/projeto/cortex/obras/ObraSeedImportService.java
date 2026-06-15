package com.projeto.cortex.obras;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ObraSeedImportService {

    private static final List<Path> SEED_PATH_CANDIDATES = List.of(
            Path.of("data", "seeds", "obras_seed.csv"),
            Path.of("..", "..", "data", "seeds", "obras_seed.csv")
    );

    private final ObraRepository obraRepository;

    public ObraSeedImportService(ObraRepository obraRepository) {
        this.obraRepository = obraRepository;
    }

    public ObraSeedImportResult importarSeedPadrao() {
        return importar(encontrarSeedPath());
    }

    private Path encontrarSeedPath() {
        for (Path path : SEED_PATH_CANDIDATES) {
            if (Files.exists(path)) {
                return path;
            }
        }

        throw new IllegalStateException(
                "Arquivo de seed não encontrado. Caminhos testados: " + SEED_PATH_CANDIDATES
        );
    }

    private ObraSeedImportResult importar(Path path) {
        int registrosLidos = 0;
        int registrosInseridos = 0;
        int registrosIgnorados = 0;
        int registrosComErro = 0;

        try {
            List<String> linhas = Files.readAllLines(path, StandardCharsets.UTF_8);

            if (linhas.isEmpty()) {
                return new ObraSeedImportResult("SUCCESS", path.toString(), 0, 0, 0, 0);
            }

            Map<String, Integer> header = mapearHeader(parseCsvLine(linhas.get(0)));

            for (int index = 1; index < linhas.size(); index++) {
                String linha = linhas.get(index);

                if (linha == null || linha.isBlank()) {
                    continue;
                }

                registrosLidos++;

                try {
                    List<String> valores = parseCsvLine(linha);

                    String codigoContrato = valorObrigatorio(header, valores, "codigo_contrato");
                    String nome = valorObrigatorio(header, valores, "nome");

                    if (obraRepository.existsByCodigoContrato(codigoContrato)) {
                        registrosIgnorados++;
                        continue;
                    }

                    Obra obra = Obra.criar(
                            codigoContrato,
                            extrairCodigoCw(codigoContrato),
                            valorOpcional(header, valores, "codigo_interno"),
                            nome,
                            valorOpcional(header, valores, "cliente"),
                            valorOpcional(header, valores, "descricao"),
                            valorOpcional(header, valores, "cidade"),
                            normalizarUf(valorOpcional(header, valores, "uf")),
                            valorOpcional(header, valores, "rodovia"),
                            "ATIVA",
                            "SEED",
                            valorOpcional(header, valores, "fonte_arquivo"),
                            valorOpcional(header, valores, "observacoes")
                    );

                    obraRepository.save(obra);
                    registrosInseridos++;
                } catch (Exception exception) {
                    registrosComErro++;
                }
            }

            return new ObraSeedImportResult(
                    "SUCCESS",
                    path.toString(),
                    registrosLidos,
                    registrosInseridos,
                    registrosIgnorados,
                    registrosComErro
            );
        } catch (Exception exception) {
            throw new RuntimeException("Falha ao importar seed de obras.", exception);
        }
    }

    private Map<String, Integer> mapearHeader(List<String> colunas) {
        Map<String, Integer> header = new HashMap<>();

        for (int index = 0; index < colunas.size(); index++) {
            header.put(colunas.get(index).trim(), index);
        }

        return header;
    }

    private String valorObrigatorio(Map<String, Integer> header, List<String> valores, String coluna) {
        String value = valorOpcional(header, valores, coluna);

        if (value == null) {
            throw new IllegalArgumentException("Campo obrigatório ausente: " + coluna);
        }

        return value;
    }

    private String valorOpcional(Map<String, Integer> header, List<String> valores, String coluna) {
        Integer index = header.get(coluna);

        if (index == null || index >= valores.size()) {
            return null;
        }

        String value = valores.get(index);

        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
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

    private String normalizarUf(String uf) {
        if (uf == null || uf.isBlank()) {
            return null;
        }

        String normalizada = uf.trim().toUpperCase(Locale.ROOT);

        if (normalizada.length() != 2) {
            return null;
        }

        return normalizada;
    }

    private List<String> parseCsvLine(String linha) {
        java.util.ArrayList<String> valores = new java.util.ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean dentroDeAspas = false;

        for (int index = 0; index < linha.length(); index++) {
            char caractere = linha.charAt(index);

            if (caractere == '"') {
                dentroDeAspas = !dentroDeAspas;
            } else if (caractere == ',' && !dentroDeAspas) {
                valores.add(atual.toString());
                atual.setLength(0);
            } else {
                atual.append(caractere);
            }
        }

        valores.add(atual.toString());
        return valores;
    }
}
