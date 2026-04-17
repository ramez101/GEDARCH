package com.btk.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "DOSSIER_EMP")
@IdClass(DossierEmpId.class)
public class DossierEmp {

    @Id
    @Column(name = "ID_DOSSIER", nullable = false)
    private Long idDossier;

    @Id
    @Column(name = "BOITE", nullable = false)
    private Integer boite;

    @Column(name = "PIN", length = 20)
    private String pin;

    @Column(name = "RELATION", length = 100)
    private String relation;

    public Long getIdDossier() {
        return idDossier;
    }

    public void setIdDossier(Long idDossier) {
        this.idDossier = idDossier;
    }

    public Integer getBoite() {
        return boite;
    }

    public void setBoite(Integer boite) {
        this.boite = boite;
    }

    public String getPin() {
        return pin;
    }

    public void setPin(String pin) {
        this.pin = pin;
    }

    public String getRelation() {
        return relation;
    }

    public void setRelation(String relation) {
        this.relation = relation;
    }
}
