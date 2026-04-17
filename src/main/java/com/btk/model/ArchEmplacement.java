package com.btk.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

import java.util.Date;

@Entity
@Table(name = "ARCH_EMPLACEMENT")
public class ArchEmplacement {

    @Id
    @Column(name = "ID_EMPLACEMENT")
    private Integer idEmplacement;

    @Column(name = "ETAGE", nullable = false)
    private Integer etage;

    @Column(name = "SALLE", nullable = false)
    private Integer salle;

    @Column(name = "RAYON", nullable = false)
    private Integer rayon;

    @Column(name = "RANGEE", nullable = false)
    private Integer rangee;

    @Column(name = "BOITE")
    private Integer boite;

    @Column(name = "FILIALE")
    private String filiale;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "DATE_AJOUT")
    private Date dateAjout;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "DATE_MODIFICATION")
    private Date dateModification;

    @Column(name = "USER_CREE")
    private String userCree;

    @Column(name = "USER_MODIFIE")
    private String userModifie;

    public Integer getIdEmplacement() { return idEmplacement; }
    public void setIdEmplacement(Integer idEmplacement) { this.idEmplacement = idEmplacement; }

    public Integer getEtage() { return etage; }
    public void setEtage(Integer etage) { this.etage = etage; }

    public Integer getSalle() { return salle; }
    public void setSalle(Integer salle) { this.salle = salle; }

    public Integer getRayon() { return rayon; }
    public void setRayon(Integer rayon) { this.rayon = rayon; }

    public Integer getRangee() { return rangee; }
    public void setRangee(Integer rangee) { this.rangee = rangee; }

    public Integer getBoite() { return boite; }
    public void setBoite(Integer boite) { this.boite = boite; }

    public String getFiliale() { return filiale; }
    public void setFiliale(String filiale) { this.filiale = filiale; }

    public Date getDateAjout() { return dateAjout; }
    public void setDateAjout(Date dateAjout) { this.dateAjout = dateAjout; }

    public Date getDateModification() { return dateModification; }
    public void setDateModification(Date dateModification) { this.dateModification = dateModification; }

    public String getUserCree() { return userCree; }
    public void setUserCree(String userCree) { this.userCree = userCree; }

    public String getUserModifie() { return userModifie; }
    public void setUserModifie(String userModifie) { this.userModifie = userModifie; }
}
