package com.btk.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "ARCH_DOSSIER")
public class ArchDossier {

    @Id
    @SequenceGenerator(name = "arch_dossier_seq", sequenceName = "ARCH_DOSSIER_SEQ", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "arch_dossier_seq")
    @Column(name = "ID_DOSSIER")
    private Long idDossier;

    @Column(name = "PORTEFEUILLE")
    private String portefeuille;

    @Column(name = "PIN")
    private String pin;

    @Column(name = "RELATION")
    private String relation;

    @Column(name = "CHARGE")
    private String charge;

    @Column(name = "TYPE_ARCHIVE")
    private String typeArchive;

    @Column(name = "ID_FILIALE")
    private String idFiliale;

    @Column(name = "FILIALE")
    private String filiale;

    public Long getIdDossier() { return idDossier; }
    public void setIdDossier(Long idDossier) { this.idDossier = idDossier; }

    public String getPortefeuille() { return portefeuille; }
    public void setPortefeuille(String portefeuille) { this.portefeuille = portefeuille; }

    public String getPin() { return pin; }
    public void setPin(String pin) { this.pin = pin; }

    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }

    public String getCharge() { return charge; }
    public void setCharge(String charge) { this.charge = charge; }

    public String getTypeArchive() { return typeArchive; }
    public void setTypeArchive(String typeArchive) { this.typeArchive = typeArchive; }

    public String getIdFiliale() { return idFiliale; }
    public void setIdFiliale(String idFiliale) { this.idFiliale = idFiliale; }

    public String getFiliale() { return filiale; }
    public void setFiliale(String filiale) { this.filiale = filiale; }
}
