package com.tpo.suby.service;

import com.tpo.suby.dto.response.fine.FineResponse;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.exception.PaymentRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FineService {

    private final JdbcTemplate jdbcTemplate;

    public FineResponse getPendingFine(Integer userId) {
        syncFineStates();

        List<FineResponse> result = jdbcTemplate.query("""
            SELECT TOP 1
                m.identificador AS fine_id,
                COALESCE(l.nombre, pd.titulo, p.descripcionCatalogo, p.descripcionCompleta) AS item_title,
                CONCAT('LOT-', RIGHT('000' + CAST(COALESCE(l.identificador, ic.identificador) AS VARCHAR), 3)) AS lot_code,
                pu.importe AS winning_bid,
                m.importeMulta AS fine_amount
            FROM multas m
            INNER JOIN pujos pu
                ON pu.identificador = m.puja
            INNER JOIN itemsCatalogo ic
                ON ic.identificador = pu.item
            INNER JOIN productos p
                ON p.identificador = ic.producto
            LEFT JOIN productos_detalle pd
                ON pd.identificador = p.identificador
            LEFT JOIN lotes l
                ON l.item = ic.identificador
            WHERE m.cliente = ?
              AND m.estado IN ('pendiente', 'vencida', 'judicial')
            ORDER BY m.fechaGeneracion DESC
        """, (rs, rowNum) -> FineResponse.builder()
                .fineId(rs.getInt("fine_id"))
                .itemTitle(rs.getString("item_title"))
                .lotCode(rs.getString("lot_code"))
                .winningBid(rs.getBigDecimal("winning_bid"))
                .finePercentage(10)
                .fineAmount(rs.getBigDecimal("fine_amount"))
                .deadlineHours(72)
                .accountStatus(resolveAccountStatus(userId))
                .build(),
        userId);

        return result.isEmpty() ? null : result.get(0);
    }

    @Transactional
    public void payFine(Integer userId, Integer fineId, Integer paymentMethodId) {
        syncFineStates();

        if (paymentMethodId == null) {
            throw new IllegalArgumentException("payment_method_id requerido");
        }

        Integer countFine = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM multas
            WHERE identificador = ?
              AND cliente = ?
              AND estado IN ('pendiente', 'vencida')
        """, Integer.class, fineId, userId);

        if (countFine == null || countFine == 0) {
            throw new NotFoundException("Multa no encontrada.");
        }

        Map<String, Object> fine = jdbcTemplate.queryForMap("""
            SELECT importeMulta
            FROM multas
            WHERE identificador = ?
              AND cliente = ?
              AND estado IN ('pendiente', 'vencida')
        """, fineId, userId);

        BigDecimal fineAmount = (BigDecimal) fine.get("importeMulta");

        List<Map<String, Object>> paymentMethods = jdbcTemplate.queryForList("""
            SELECT montoDisponible, montoUsado, estado, tipo
            FROM mediosDePago
            WHERE identificador = ?
              AND cliente = ?
        """, paymentMethodId, userId);

        if (paymentMethods.isEmpty()) {
            throw new NotFoundException("Multa no encontrada.");
        }

        Map<String, Object> paymentMethod = paymentMethods.get(0);

        String estado = (String) paymentMethod.get("estado");
        String tipo = (String) paymentMethod.get("tipo");

        if (!"verificado".equalsIgnoreCase(estado)) {
            throw new PaymentRequiredException("Saldo insuficiente para abonar la multa.");
        }

        if (!"tarjeta_credito".equalsIgnoreCase(tipo)) {
            BigDecimal available = (BigDecimal) paymentMethod.get("montoDisponible");
            BigDecimal used = (BigDecimal) paymentMethod.get("montoUsado");

            if (available == null) {
                available = BigDecimal.ZERO;
            }

            if (used == null) {
                used = BigDecimal.ZERO;
            }

            BigDecimal remaining = available.subtract(used);
            if (remaining.compareTo(fineAmount) < 0) {
                throw new PaymentRequiredException("Saldo insuficiente para abonar la multa.");
            }

            jdbcTemplate.update("""
                UPDATE mediosDePago
                SET montoUsado = montoUsado + ?
                WHERE identificador = ?
                  AND cliente = ?
            """, fineAmount, paymentMethodId, userId);
        }

        jdbcTemplate.update("""
            UPDATE multas
            SET estado = 'pagada',
                fechaPago = GETDATE()
            WHERE identificador = ?
              AND cliente = ?
        """, fineId, userId);

        refreshUserAccountStatus(userId);
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void syncFineStates() {
        jdbcTemplate.update("""
                UPDATE multas
                SET estado = 'vencida'
                WHERE estado = 'pendiente'
                  AND fechaPago IS NULL
                  AND fechaLimitePago <= GETDATE()
                """);

        jdbcTemplate.update("""
                UPDATE multas
                SET estado = 'judicial'
                WHERE estado = 'vencida'
                  AND fechaPago IS NULL
                  AND DATEADD(HOUR, 72, fechaLimitePago) <= GETDATE()
                """);

        List<Integer> affectedUsers = jdbcTemplate.query("""
                SELECT DISTINCT cliente
                FROM multas
                WHERE estado IN ('pendiente', 'vencida', 'judicial')
                """, (rs, rowNum) -> rs.getInt("cliente"));

        for (Integer userId : affectedUsers) {
            refreshUserAccountStatus(userId);
        }
    }

    private void refreshUserAccountStatus(Integer userId) {
        Integer judicialCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM multas
                WHERE cliente = ?
                  AND estado = 'judicial'
                """, Integer.class, userId);

        if (judicialCount != null && judicialCount > 0) {
            jdbcTemplate.update("""
                    UPDATE usuarios_app
                    SET estadoApp = 'judicial',
                        bloqueadoHasta = NULL
                    WHERE identificador = ?
                    """, userId);
            return;
        }

        Integer blockedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM multas
                WHERE cliente = ?
                  AND estado IN ('pendiente', 'vencida')
                """, Integer.class, userId);

        if (blockedCount != null && blockedCount > 0) {
            jdbcTemplate.update("""
                    UPDATE usuarios_app
                    SET estadoApp = 'bloqueado',
                        bloqueadoHasta = DATEADD(HOUR, 72, GETDATE())
                    WHERE identificador = ?
                    """, userId);
            return;
        }

        jdbcTemplate.update("""
                UPDATE usuarios_app
                SET estadoApp = 'activo',
                    bloqueadoHasta = NULL,
                    intentosFallidos = 0
                WHERE identificador = ?
                """, userId);
    }

    private String resolveAccountStatus(Integer userId) {
        String status = jdbcTemplate.queryForObject("""
                SELECT estadoApp
                FROM usuarios_app
                WHERE identificador = ?
                """, String.class, userId);
        return status == null ? "activo" : status;
    }
}
