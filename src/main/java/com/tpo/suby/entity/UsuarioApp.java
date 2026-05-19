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
    @Column(name = "identificador")
    private Integer identificador;

    @OneToOne
    @MapsId
    @JoinColumn(name = "identificador")
    private Persona persona;

    private String email;

    @Column(name = "passwordHash")
    private String passwordHash;

    @Column(name = "tokenRecuperacion")
    private String tokenRecuperacion;

    @Column(name = "tokenExpira")
    private LocalDateTime tokenExpira;

    @Column(name = "estadoApp")
    private String estadoApp;

    @Column(name = "ultimoLogin")
    private LocalDateTime ultimoLogin;

    @Column(name = "intentosFallidos")
    private Integer intentosFallidos;

    @Column(name = "bloqueadoHasta")
    private LocalDateTime bloqueadoHasta;

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
        return !"bloqueado".equalsIgnoreCase(estadoApp);
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return !"inactivo".equalsIgnoreCase(estadoApp);
    }
}