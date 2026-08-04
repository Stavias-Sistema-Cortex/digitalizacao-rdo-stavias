package com.projeto.cortex.obras;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "obra")
public class Obra {

    @Id
    private String id;

    @Column(name = "codigo_contrato", nullable = false)
    private String codigoContrato;

    @Column(name = "codigo_cw")
    private String codigoCw;

    @Column(name = "codigo_interno")
    private String codigoInterno;

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "cliente")
    private String cliente;

    @Column(name = "descricao")
    private String descricao;

    @Column(name = "cidade")
    private String cidade;

    @Column(name = "uf")
    private String uf;

    @Column(name = "rodovia")
    private String rodovia;

    @Column(name = "latitude")
    private BigDecimal latitude;

    @Column(name = "longitude")
    private BigDecimal longitude;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "fonte_criacao", nullable = false)
    private String fonteCriacao;

    @Column(name = "fonte_arquivo")
    private String fonteArquivo;

    @Column(name = "observacoes")
    private String observacoes;

    @Column(name = "criado_em")
    private LocalDateTime criadoEm;

    @Column(name = "atualizado_em")
    private LocalDateTime atualizadoEm;

    @Column(name = "arquivado_em")
    private LocalDateTime arquivadoEm;

    @Column(name = "versao_linha")
    private long versaoLinha;

    protected Obra() {
    }

    private Obra(
            String codigoContrato,
            String codigoCw,
            String codigoInterno,
            String nome,
            String cliente,
            String descricao,
            String cidade,
            String uf,
            String rodovia,
            String status,
            String fonteCriacao,
            String fonteArquivo,
            String observacoes
    ) {
        LocalDateTime agora = LocalDateTime.now();

        this.id = UUID.randomUUID().toString();
        this.codigoContrato = codigoContrato;
        this.codigoCw = codigoCw;
        this.codigoInterno = codigoInterno;
        this.nome = nome;
        this.cliente = cliente;
        this.descricao = descricao;
        this.cidade = cidade;
        this.uf = uf;
        this.rodovia = rodovia;
        this.status = status;
        this.fonteCriacao = fonteCriacao;
        this.fonteArquivo = fonteArquivo;
        this.observacoes = observacoes;
        this.criadoEm = agora;
        this.atualizadoEm = agora;
        this.versaoLinha = 1;
    }

    public static Obra criar(
            String codigoContrato,
            String codigoCw,
            String codigoInterno,
            String nome,
            String cliente,
            String descricao,
            String cidade,
            String uf,
            String rodovia,
            String status,
            String fonteCriacao,
            String fonteArquivo,
            String observacoes
    ) {
        return new Obra(
                codigoContrato,
                codigoCw,
                codigoInterno,
                nome,
                cliente,
                descricao,
                cidade,
                uf,
                rodovia,
                status,
                fonteCriacao,
                fonteArquivo,
                observacoes
        );
    }

    public void editar(
            String codigoContrato,
            String codigoCw,
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
        exigirNaoArquivada();
        this.codigoContrato = codigoContrato;
        this.codigoCw = codigoCw;
        this.codigoInterno = codigoInterno;
        this.nome = nome;
        this.cliente = cliente;
        this.descricao = descricao;
        this.cidade = cidade;
        this.uf = uf;
        this.rodovia = rodovia;
        this.fonteArquivo = fonteArquivo;
        this.observacoes = observacoes;
        tocar();
    }

    public void desativar() {
        exigirNaoArquivada();
        this.status = "INATIVA";
        tocar();
    }

    /**
     * O caminho de volta de {@link #desativar()}.
     *
     * <p>Desativar era porta de mão única: o status ia para INATIVA e nada o
     * trazia de volta. Restaurar não servia — ela desfaz o arquivamento e não
     * toca no status, então uma obra arquivada enquanto inativa voltava
     * inativa. Quem desativasse por engano ficava sem saída pelo produto.
     *
     * <p>Arquivada continua recusando: obra no arquivo aceita só restauração, e
     * essa é a mesma regra que desativar já obedecia.
     */
    public void ativar() {
        exigirNaoArquivada();
        this.status = "ATIVA";
        tocar();
    }

    public void arquivar() {
        exigirNaoArquivada();
        LocalDateTime agora = LocalDateTime.now();
        this.arquivadoEm = agora;
        tocar(agora);
    }

    public void restaurar() {
        if (arquivadoEm == null) {
            throw new IllegalStateException("A obra não está arquivada.");
        }
        this.arquivadoEm = null;
        tocar();
    }

    private void exigirNaoArquivada() {
        if (arquivadoEm != null) {
            throw new IllegalStateException(
                    "A obra arquivada aceita apenas restauração."
            );
        }
    }

    private void tocar() {
        tocar(LocalDateTime.now());
    }

    private void tocar(LocalDateTime agora) {
        this.atualizadoEm = agora;
        this.versaoLinha++;
    }

    public String getId() {
        return id;
    }

    public String getCodigoContrato() {
        return codigoContrato;
    }

    public String getCodigoCw() {
        return codigoCw;
    }

    public String getCodigoInterno() {
        return codigoInterno;
    }

    public String getNome() {
        return nome;
    }

    public String getCliente() {
        return cliente;
    }

    public String getDescricao() {
        return descricao;
    }

    public String getCidade() {
        return cidade;
    }

    public String getUf() {
        return uf;
    }

    public String getRodovia() {
        return rodovia;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public String getStatus() {
        return status;
    }

    public String getFonteCriacao() {
        return fonteCriacao;
    }

    public String getFonteArquivo() {
        return fonteArquivo;
    }

    public String getObservacoes() {
        return observacoes;
    }

    public LocalDateTime getCriadoEm() {
        return criadoEm;
    }

    public LocalDateTime getAtualizadoEm() {
        return atualizadoEm;
    }

    public LocalDateTime getArquivadoEm() {
        return arquivadoEm;
    }

    public long getVersaoLinha() {
        return versaoLinha;
    }
}
