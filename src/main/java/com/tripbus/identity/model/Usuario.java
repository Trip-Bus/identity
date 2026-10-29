package com.tripbus.identity.model;

import com.tripbus.identity.util.TextoBusqueda;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "documento_identidad", nullable = false, unique = true)
    private String documentoIdentidad;

    @Column(nullable = false)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RolUsuario rol = RolUsuario.PASAJERO;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(nullable = false)
    private boolean activo = true;

    @CreationTimestamp
    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private Instant fechaCreacion;

    @UpdateTimestamp
    @Column(name = "fecha_actualizacion", nullable = false)
    private Instant fechaActualizacion;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /** US-128: celular colombiano (10 dígitos empezando por 3); opcional. */
    @Column(length = 10)
    private String telefono;

    /** US-130: no verificar el correo no bloquea el login; va en el claim emailVerificado. */
    @Column(name = "email_verificado", nullable = false)
    private boolean emailVerificado;

    @Column(name = "email_verificado_en")
    private Instant emailVerificadoEn;

    /** US-102: nombre, email y documento sin mayúsculas ni tildes (ver TextoBusqueda). */
    @Column(nullable = false, length = 600)
    private String busqueda;

    protected Usuario() {
    }

    @PrePersist
    @PreUpdate
    void actualizarBusqueda() {
        this.busqueda = TextoBusqueda.deUsuario(nombre, email, documentoIdentidad);
    }

    public Usuario(String email, String passwordHash, String documentoIdentidad, String nombre) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.documentoIdentidad = documentoIdentidad;
        this.nombre = nombre;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDocumentoIdentidad() {
        return documentoIdentidad;
    }

    public void setDocumentoIdentidad(String documentoIdentidad) {
        this.documentoIdentidad = documentoIdentidad;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public RolUsuario getRol() {
        return rol;
    }

    public void setRol(RolUsuario rol) {
        this.rol = rol;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
    }

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }

    public Instant getFechaCreacion() {
        return fechaCreacion;
    }

    public Instant getFechaActualizacion() {
        return fechaActualizacion;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(Instant passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }

    public String getTelefono() {
        return telefono;
    }

    public void setTelefono(String telefono) {
        this.telefono = telefono;
    }

    public boolean isEmailVerificado() {
        return emailVerificado;
    }

    public void setEmailVerificado(boolean emailVerificado) {
        this.emailVerificado = emailVerificado;
    }

    public Instant getEmailVerificadoEn() {
        return emailVerificadoEn;
    }

    public void setEmailVerificadoEn(Instant emailVerificadoEn) {
        this.emailVerificadoEn = emailVerificadoEn;
    }
}
