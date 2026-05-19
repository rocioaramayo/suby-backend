package com.tpo.suby.entity;

import jakarta.persistence.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;

@Entity
@Table(name = "usuarios_app")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsuarioApp implements UserDetails {

    @Id
    private Integer identificador;

    @OneToOne
    @MapsId
    @JoinColumn(name = "identificador")
    private Persona persona;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "token_recuperacion")
    private String tokenRecuperacion;

    @Column(name = "token_expira")
    private LocalDateTime tokenExpira;

    @Column(name = "estado_app")
    private String estadoApp;

    @Column(name = "ultimo_login")
    private LocalDateTime ultimoLogin;

    @Column(name = "intentos_fallidos")
    private Integer intentosFallidos;

    @Column(name = "bloqueado_hasta")
    private LocalDateTime bloqueadoHasta;

    @Column(name = "primer_login")
    private Boolean primerLogin;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.emptyList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return bloqueadoHasta == null || bloqueadoHasta.isBefore(LocalDateTime.now());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        if (estadoApp == null || estadoApp.isBlank()) {
            return true;
        }

        String normalizedEstado = estadoApp.trim().toUpperCase();
        return !normalizedEstado.equals("INACTIVO") && !normalizedEstado.equals("BLOQUEADO");
    }
}