package com.tpo.suby.repository;

import com.tpo.suby.entity.UsuarioApp;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UsuarioAppRepository
        extends JpaRepository<UsuarioApp, Integer> {

    Optional<UsuarioApp> findByEmail(String email);

    boolean existsByEmail(String email);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update UsuarioApp u
               set u.passwordHash = :passwordHash,
                   u.ultimoLogin = :ultimoLogin,
                   u.intentosFallidos = 0,
                   u.bloqueadoHasta = null
             where u.identificador = :userId
            """)
    int updatePassword(
            @Param("userId") Integer userId,
            @Param("passwordHash") String passwordHash,
            @Param("ultimoLogin") java.time.LocalDateTime ultimoLogin
    );
}