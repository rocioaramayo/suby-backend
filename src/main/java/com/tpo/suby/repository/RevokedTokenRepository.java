package com.tpo.suby.repository;

import com.tpo.suby.entity.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, Integer> {

    boolean existsByTokenHash(String tokenHash);
}