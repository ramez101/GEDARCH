package com.btk.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * JPA Entity mapped to the UTILISATEUR table.
 */
@Entity
@Table(name = "arch_utilisateurs")
public class Utilisateur {

    /** Code utilisateur – primary key */
    @Id
    @Column(name = "CUTI", length = 24, nullable = false)
    private String cuti;

    /** Username (Unix login) */
    @Column(name = "UNIX", length = 20)
    private String unix;

    /** Nom complet */
    @Column(name = "LIB", length = 20)
    private String lib;

    /** Profil utilisateur */
    @Column(name = "PUTI", length = 20)
    private String puti;

    /** Agence */
    @Column(name = "AGE", length = 8)
    private String age;

    /** Role utilisateur */
    @Column(name = "ROLE", length = 30)
    private String role;

    /** Etat actif calcule a partir de la colonne ACTIVE (non persiste via JPA) */
    @Transient
    private boolean actif = true;

    // ── Constructors ─────────────────────────────────────────────────────────
    public Utilisateur() {}

    // ── Getters & Setters ─────────────────────────────────────────────────────
    public String getCuti()            { return cuti; }
    public void   setCuti(String cuti) { this.cuti = cuti; }

    public String getUnix()            { return unix; }
    public void   setUnix(String unix) { this.unix = unix; }

    public String getLib()             { return lib; }
    public void   setLib(String lib)   { this.lib = lib; }

    public String getPuti()            { return puti; }
    public void   setPuti(String puti) { this.puti = puti; }

    public String getAge()             { return age; }
    public void   setAge(String age)   { this.age = age; }

    public String getRole()            { return role; }
    public void   setRole(String role) { this.role = role; }

    public boolean isActif()               { return actif; }
    public void    setActif(boolean actif) { this.actif = actif; }
}
