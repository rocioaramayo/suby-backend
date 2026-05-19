package com.tpo.suby.repository;

import com.tpo.suby.entity.UsuarioApp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UsuarioAppRepository
        extends JpaRepository<UsuarioApp, Integer> {

    Optional<UsuarioApp> findByEmail(String email);

    boolean existsByEmail(String email);
}