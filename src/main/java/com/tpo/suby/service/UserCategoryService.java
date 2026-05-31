package com.tpo.suby.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserCategoryService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public String refreshCategory(Integer userId) {
        CategorySnapshot snapshot = jdbcTemplate.queryForObject("""
                SELECT
                    COALESCE(c.categoria, 'comun') AS current_category,
                    COALESCE(wins.auctions_won, 0) AS auctions_won,
                    COALESCE(payments.payment_types_registered, 0) AS payment_types_registered
                FROM clientes c
                OUTER APPLY (
                    SELECT COUNT(*) AS auctions_won
                    FROM pujos pu
                    JOIN asistentes a ON a.identificador = pu.asistente
                    WHERE a.cliente = c.identificador
                      AND pu.ganador = 'si'
                ) wins
                OUTER APPLY (
                    SELECT COUNT(DISTINCT mdp.tipo) AS payment_types_registered
                    FROM mediosDePago mdp
                    WHERE mdp.cliente = c.identificador
                ) payments
                WHERE c.identificador = ?
                """, (rs, rowNum) -> new CategorySnapshot(
                rs.getString("current_category"),
                rs.getInt("auctions_won"),
                rs.getInt("payment_types_registered")
        ), userId);

        if (snapshot == null) {
            return "comun";
        }

        String calculated = calculateCategory(snapshot.auctionsWon(), snapshot.paymentTypesRegistered());
        if (!normalize(snapshot.currentCategory()).equals(calculated)) {
            jdbcTemplate.update("""
                    UPDATE clientes
                    SET categoria = ?
                    WHERE identificador = ?
                    """, calculated, userId);
        }

        return calculated;
    }

    private String calculateCategory(Integer auctionsWon, Integer paymentTypesRegistered) {
        int wins = auctionsWon == null ? 0 : auctionsWon;
        int paymentTypes = paymentTypesRegistered == null ? 0 : paymentTypesRegistered;

        if (wins >= 10 && paymentTypes >= 3) {
            return "platino";
        }
        if (wins >= 5 && paymentTypes >= 2) {
            return "oro";
        }
        if (wins >= 3 && paymentTypes >= 1) {
            return "plata";
        }
        if (wins >= 1 && paymentTypes >= 1) {
            return "especial";
        }
        return "comun";
    }

    private String normalize(String category) {
        return category == null ? "comun" : category.trim().toLowerCase();
    }

    private record CategorySnapshot(
            String currentCategory,
            Integer auctionsWon,
            Integer paymentTypesRegistered
    ) {
    }
}
