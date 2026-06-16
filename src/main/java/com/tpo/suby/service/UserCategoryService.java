package com.tpo.suby.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserCategoryService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public String refreshCategory(Integer userId) {
        try {
            String currentCategory = jdbcTemplate.queryForObject("""
                    SELECT TOP 1 COALESCE(categoria, 'comun')
                    FROM clientes
                    WHERE identificador = ?
                    """, String.class, userId);
            return normalize(currentCategory);
        } catch (EmptyResultDataAccessException ex) {
            return "comun";
        }
    }

    private String normalize(String category) {
        return category == null ? "comun" : category.trim().toLowerCase();
    }
}
