package com.projeto.cortex.programacoes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "programacao_operacional")
public class ProgramacaoOperacional {

    @Id
    private String id;

    @Column(name = "obra_id", nullable = false)
    private String obraId;

    @Column(name = "codigo_contrato_origem")
    private String codigoContratoOrigem;

    @Column(name = "obra_nome_origem")
    private String obraNomeOrigem;

    @Column(name = "data_programacao", nullable = false)
    private LocalDate dataProgramacao;

    @Column(name = "equipe")
    private String equipe;

    @Column(name = "encarregado")
    private String encarregado;

    @Column(name = "engenheiro")
    private String engenheiro;

    @Column(name = "cliente")
    private String cliente;

    @Column(name = "servico")
    private String servico;

    @Column(name = "tipo_servico")
    private String tipoServico;

    @Column(name = "cidade")
    private String cidade;

    @Column(name = "uf")
    private String uf;

    @Column(name = "rodovia")
    private String rodovia;

    @Column(name = "sentido")
    private String sentido;

    @Column(name = "faixa")
    private String faixa;

    @Column(name = "km_inicial")
    private String kmInicial;

    @Column(name = "km_final")
    private String kmFinal;

    @Column(name = "extensao_m")
    private BigDecimal extensaoM;

    @Column(name = "largura_m")
    private BigDecimal larguraM;

    @Column(name = "espessura_cm")
    private BigDecimal espessuraCm;

    @Column(name = "area_m2")
    private BigDecimal areaM2;

    @Column(name = "volume_m3")
    private BigDecimal volumeM3;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "fonte_criacao", nullable = false)
    private String fonteCriacao;

    @Column(name = "fonte_arquivo")
    private String fonteArquivo;

    @Column(name = "linha_origem")
    private Integer linhaOrigem;

    @Column(name = "hash_origem")
    private String hashOrigem;

    @Column(name = "chave_negocio")
    private String chaveNegocio;

    @Column(name = "observacoes")
    private String observacoes;

    @Column(name = "criado_em")
    private LocalDateTime criadoEm;

    @Column(name = "atualizado_em")
    private LocalDateTime atualizadoEm;

    @Column(name = "cancelado_em")
    private LocalDateTime canceladoEm;

    @Column(name = "versao_linha")
    private long versaoLinha;

    protected ProgramacaoOperacional() {
    }

    private ProgramacaoOperacional(
            String obraId,
            String codigoContratoOrigem,
            String obraNomeOrigem,
            LocalDate dataProgramacao,
            String equipe,
            String encarregado,
            String engenheiro,
            String cliente,
            String servico,
            String tipoServico,
            String cidade,
            String uf,
            String rodovia,
            String sentido,
            String faixa,
            String kmInicial,
            String kmFinal,
            BigDecimal extensaoM,
            BigDecimal larguraM,
            BigDecimal espessuraCm,
            BigDecimal areaM2,
            BigDecimal volumeM3,
            String status,
            String fonteCriacao,
            String fonteArquivo,
            Integer linhaOrigem,
            String hashOrigem,
            String chaveNegocio,
            String observacoes
    ) {
        LocalDateTime agora = LocalDateTime.now();

        this.id = UUID.randomUUID().toString();
        this.obraId = obraId;
        this.codigoContratoOrigem = codigoContratoOrigem;
        this.obraNomeOrigem = obraNomeOrigem;
        this.dataProgramacao = dataProgramacao;
        this.equipe = equipe;
        this.encarregado = encarregado;
        this.engenheiro = engenheiro;
        this.cliente = cliente;
        this.servico = servico;
        this.tipoServico = tipoServico;
        this.cidade = cidade;
        this.uf = uf;
        this.rodovia = rodovia;
        this.sentido = sentido;
        this.faixa = faixa;
        this.kmInicial = kmInicial;
        this.kmFinal = kmFinal;
        this.extensaoM = extensaoM;
        this.larguraM = larguraM;
        this.espessuraCm = espessuraCm;
        this.areaM2 = areaM2;
        this.volumeM3 = volumeM3;
        this.status = status;
        this.fonteCriacao = fonteCriacao;
        this.fonteArquivo = fonteArquivo;
        this.linhaOrigem = linhaOrigem;
        this.hashOrigem = hashOrigem;
        this.chaveNegocio = chaveNegocio;
        this.observacoes = observacoes;
        this.criadoEm = agora;
        this.atualizadoEm = agora;
        this.versaoLinha = 0;
    }

    public static ProgramacaoOperacional criar(
            String obraId,
            String codigoContratoOrigem,
            String obraNomeOrigem,
            LocalDate dataProgramacao,
            String equipe,
            String encarregado,
            String engenheiro,
            String cliente,
            String servico,
            String tipoServico,
            String cidade,
            String uf,
            String rodovia,
            String sentido,
            String faixa,
            String kmInicial,
            String kmFinal,
            BigDecimal extensaoM,
            BigDecimal larguraM,
            BigDecimal espessuraCm,
            BigDecimal areaM2,
            BigDecimal volumeM3,
            String status,
            String fonteCriacao,
            String fonteArquivo,
            Integer linhaOrigem,
            String hashOrigem,
            String observacoes
    ) {
        return new ProgramacaoOperacional(
                obraId,
                codigoContratoOrigem,
                obraNomeOrigem,
                dataProgramacao,
                equipe,
                encarregado,
                engenheiro,
                cliente,
                servico,
                tipoServico,
                cidade,
                uf,
                rodovia,
                sentido,
                faixa,
                kmInicial,
                kmFinal,
                extensaoM,
                larguraM,
                espessuraCm,
                areaM2,
                volumeM3,
                status,
                fonteCriacao,
                fonteArquivo,
                linhaOrigem,
                hashOrigem,
                null,
                observacoes
        );
    }

    public static ProgramacaoOperacional criarComChaveNegocio(
            String obraId,
            String codigoContratoOrigem,
            String obraNomeOrigem,
            LocalDate dataProgramacao,
            String equipe,
            String encarregado,
            String engenheiro,
            String cliente,
            String servico,
            String tipoServico,
            String cidade,
            String uf,
            String rodovia,
            String sentido,
            String faixa,
            String kmInicial,
            String kmFinal,
            BigDecimal extensaoM,
            BigDecimal larguraM,
            BigDecimal espessuraCm,
            BigDecimal areaM2,
            BigDecimal volumeM3,
            String status,
            String fonteCriacao,
            String fonteArquivo,
            Integer linhaOrigem,
            String hashOrigem,
            String chaveNegocio,
            String observacoes
    ) {
        return new ProgramacaoOperacional(
                obraId,
                codigoContratoOrigem,
                obraNomeOrigem,
                dataProgramacao,
                equipe,
                encarregado,
                engenheiro,
                cliente,
                servico,
                tipoServico,
                cidade,
                uf,
                rodovia,
                sentido,
                faixa,
                kmInicial,
                kmFinal,
                extensaoM,
                larguraM,
                espessuraCm,
                areaM2,
                volumeM3,
                status,
                fonteCriacao,
                fonteArquivo,
                linhaOrigem,
                hashOrigem,
                chaveNegocio,
                observacoes
        );
    }

    public boolean temMesmoHashOrigem(String novoHashOrigem) {
        return java.util.Objects.equals(this.hashOrigem, novoHashOrigem);
    }

    public void atualizarDeSeed(
            String obraId,
            String codigoContratoOrigem,
            String obraNomeOrigem,
            LocalDate dataProgramacao,
            String equipe,
            String encarregado,
            String engenheiro,
            String cliente,
            String servico,
            String tipoServico,
            String cidade,
            String uf,
            String rodovia,
            String sentido,
            String faixa,
            String kmInicial,
            String kmFinal,
            BigDecimal extensaoM,
            BigDecimal larguraM,
            BigDecimal espessuraCm,
            BigDecimal areaM2,
            BigDecimal volumeM3,
            String status,
            String fonteCriacao,
            String fonteArquivo,
            Integer linhaOrigem,
            String hashOrigem,
            String chaveNegocio,
            String observacoes
    ) {
        this.obraId = obraId;
        this.codigoContratoOrigem = codigoContratoOrigem;
        this.obraNomeOrigem = obraNomeOrigem;
        this.dataProgramacao = dataProgramacao;
        this.equipe = equipe;
        this.encarregado = encarregado;
        this.engenheiro = engenheiro;
        this.cliente = cliente;
        this.servico = servico;
        this.tipoServico = tipoServico;
        this.cidade = cidade;
        this.uf = uf;
        this.rodovia = rodovia;
        this.sentido = sentido;
        this.faixa = faixa;
        this.kmInicial = kmInicial;
        this.kmFinal = kmFinal;
        this.extensaoM = extensaoM;
        this.larguraM = larguraM;
        this.espessuraCm = espessuraCm;
        this.areaM2 = areaM2;
        this.volumeM3 = volumeM3;
        this.status = status;
        this.fonteCriacao = fonteCriacao;
        this.fonteArquivo = fonteArquivo;
        this.linhaOrigem = linhaOrigem;
        this.hashOrigem = hashOrigem;
        this.chaveNegocio = chaveNegocio;
        this.observacoes = observacoes;
        this.canceladoEm = null;
        this.atualizadoEm = LocalDateTime.now();
        this.versaoLinha++;
    }

    public String getId() { return id; }
    public String getObraId() { return obraId; }
    public String getCodigoContratoOrigem() { return codigoContratoOrigem; }
    public String getObraNomeOrigem() { return obraNomeOrigem; }
    public LocalDate getDataProgramacao() { return dataProgramacao; }
    public String getEquipe() { return equipe; }
    public String getEncarregado() { return encarregado; }
    public String getEngenheiro() { return engenheiro; }
    public String getCliente() { return cliente; }
    public String getServico() { return servico; }
    public String getTipoServico() { return tipoServico; }
    public String getCidade() { return cidade; }
    public String getUf() { return uf; }
    public String getRodovia() { return rodovia; }
    public String getSentido() { return sentido; }
    public String getFaixa() { return faixa; }
    public String getKmInicial() { return kmInicial; }
    public String getKmFinal() { return kmFinal; }
    public BigDecimal getExtensaoM() { return extensaoM; }
    public BigDecimal getLarguraM() { return larguraM; }
    public BigDecimal getEspessuraCm() { return espessuraCm; }
    public BigDecimal getAreaM2() { return areaM2; }
    public BigDecimal getVolumeM3() { return volumeM3; }
    public String getStatus() { return status; }
    public String getFonteCriacao() { return fonteCriacao; }
    public String getFonteArquivo() { return fonteArquivo; }
    public Integer getLinhaOrigem() { return linhaOrigem; }
    public String getHashOrigem() { return hashOrigem; }
    public String getObservacoes() { return observacoes; }
    public LocalDateTime getCriadoEm() { return criadoEm; }
    public LocalDateTime getAtualizadoEm() { return atualizadoEm; }
    public LocalDateTime getCanceladoEm() { return canceladoEm; }
    public long getVersaoLinha() { return versaoLinha; }
}
