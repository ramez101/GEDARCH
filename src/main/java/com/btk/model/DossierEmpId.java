package com.btk.model;

import java.io.Serializable;
import java.util.Objects;

public class DossierEmpId implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long idDossier;
    private Integer boite;

    public DossierEmpId() {
    }

    public DossierEmpId(Long idDossier, Integer boite) {
        this.idDossier = idDossier;
        this.boite = boite;
    }

    public Long getIdDossier() {
        return idDossier;
    }

    public Integer getBoite() {
        return boite;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DossierEmpId)) {
            return false;
        }
        DossierEmpId that = (DossierEmpId) other;
        return Objects.equals(idDossier, that.idDossier)
                && Objects.equals(boite, that.boite);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idDossier, boite);
    }
}
