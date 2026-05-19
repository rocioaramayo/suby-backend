package com.tpo.suby.repository;

import com.tpo.suby.entity.OnboardingUsuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface OnboardingUsuarioRepository extends JpaRepository<OnboardingUsuario, Integer> {

	Optional<OnboardingUsuario> findByEmail(String email);

	List<OnboardingUsuario> findByEstado(String estado);
}