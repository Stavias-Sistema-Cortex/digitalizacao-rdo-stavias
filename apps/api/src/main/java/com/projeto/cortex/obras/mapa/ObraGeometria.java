package com.projeto.cortex.obras.mapa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "obra_geometria")
class ObraGeometria {

    @Id
    private String id;

    @Column(name = "obra_id", nullable = false)
    private String obraId;

    @Column(name = "categoria", nullable = false)
    private String categoria;

    @Column(name = "objeto_tipo")
    private String objetoTipo;

    @Column(name = "objeto_id")
    private String objetoId;

    @Column(name = "tipo_geometria", nullable = false)
    private String tipoGeometria;

    /*
     * O JdbcTypeCode é o que faz a gravação existir. A coluna é jsonb, e um
     * String sem essa anotação é bindado como VARCHAR — o PostgreSQL recusa a
     * conversão implícita, e cada apontamento de geometria vindo do campo
     * morria nessa recusa, retentando para sempre. O MySQL aceitava, e foi por
     * isso que o defeito atravessou os testes que rodavam contra ele.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "geometria_json", nullable = false, columnDefinition = "json")
    private String geometriaJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "propriedades_json", nullable = false, columnDefinition = "json")
    private String propriedadesJson;

    @Column(name = "fonte", nullable = false)
    private String fonte;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "valido_desde", nullable = false)
    private LocalDateTime validoDesde;

    @Column(name = "valido_ate")
    private LocalDateTime validoAte;

    @Column(name = "motivo_encerramento")
    private String motivoEncerramento;

    @Version
    @Column(name = "versao_linha", nullable = false)
    private long versaoLinha;

    @Column(name = "criado_por", nullable = false)
    private String criadoPor;

    @Column(name = "atualizado_por", nullable = false)
    private String atualizadoPor;

    @Column(name = "criado_em", nullable = false)
    private LocalDateTime criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    private LocalDateTime atualizadoEm;

    protected ObraGeometria() {
    }

    static ObraGeometria criar(
            String obraId,
            String categoria,
            String objetoTipo,
            String objetoId,
            String tipoGeometria,
            String geometriaJson,
            String propriedadesJson,
            String fonte,
            LocalDateTime validoDesde,
            String actorId
    ) {
        LocalDateTime now = LocalDateTime.now();
        ObraGeometria feature = new ObraGeometria();
        feature.id = UUID.randomUUID().toString();
        feature.obraId = obraId;
        feature.categoria = categoria;
        feature.objetoTipo = objetoTipo;
        feature.objetoId = objetoId;
        feature.tipoGeometria = tipoGeometria;
        feature.geometriaJson = geometriaJson;
        feature.propriedadesJson = propriedadesJson;
        feature.fonte = fonte;
        feature.status = "ATIVA";
        feature.validoDesde = validoDesde == null ? now : validoDesde;
        feature.criadoPor = actorId;
        feature.atualizadoPor = actorId;
        feature.criadoEm = now;
        feature.atualizadoEm = now;
        return feature;
    }

    void atualizar(
            String categoria,
            String objetoTipo,
            String objetoId,
            String tipoGeometria,
            String geometriaJson,
            String propriedadesJson,
            String fonte,
            LocalDateTime validoDesde,
            String actorId
    ) {
        this.categoria = categoria;
        this.objetoTipo = objetoTipo;
        this.objetoId = objetoId;
        this.tipoGeometria = tipoGeometria;
        this.geometriaJson = geometriaJson;
        this.propriedadesJson = propriedadesJson;
        this.fonte = fonte;
        this.validoDesde = validoDesde == null ? this.validoDesde : validoDesde;
        this.atualizadoPor = actorId;
        this.atualizadoEm = LocalDateTime.now();
    }

    void encerrar(LocalDateTime validoAte, String motivo, String actorId) {
        this.status = "ENCERRADA";
        this.validoAte = validoAte == null ? LocalDateTime.now() : validoAte;
        this.motivoEncerramento = motivo;
        this.atualizadoPor = actorId;
        this.atualizadoEm = LocalDateTime.now();
    }

    String getId() { return id; }
    String getObraId() { return obraId; }
    String getCategoria() { return categoria; }
    String getObjetoTipo() { return objetoTipo; }
    String getObjetoId() { return objetoId; }
    String getTipoGeometria() { return tipoGeometria; }
    String getGeometriaJson() { return geometriaJson; }
    String getPropriedadesJson() { return propriedadesJson; }
    String getFonte() { return fonte; }
    String getStatus() { return status; }
    LocalDateTime getValidoDesde() { return validoDesde; }
    LocalDateTime getValidoAte() { return validoAte; }
    String getMotivoEncerramento() { return motivoEncerramento; }
    long getVersaoLinha() { return versaoLinha; }
    String getCriadoPor() { return criadoPor; }
    String getAtualizadoPor() { return atualizadoPor; }
    LocalDateTime getCriadoEm() { return criadoEm; }
    LocalDateTime getAtualizadoEm() { return atualizadoEm; }
}
