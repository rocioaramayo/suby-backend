package com.tpo.suby.service;

import com.tpo.suby.dto.response.difference.DifferenceResponse;
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
public class DifferenceService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Retorna la diferencia de saldo pendiente del usuario (la primera activa, si existe).
     */
    public DifferenceResponse getPendingDifference(Integer userId) {
        syncDifferenceStates();

        List<DifferenceResponse> result = jdbcTemplate.query("""
                SELECT TOP 1
                    d.identificador           AS difference_id,
                    COALESCE(pd.titulo, p.descripcionCatalogo, p.descripcionCompleta) AS item_title,
                    CONCAT('LOT-', RIGHT('000' + CAST(ic.identificador AS VARCHAR), 3)) AS lot_code,
                    pu.importe                AS winning_bid,
                    d.saldoDisponible         AS available_balance,
                    d.importeDiferencia       AS difference_amount,
                    d.estado                  AS status
                FROM diferencias_saldo d
                INNER JOIN pujos pu
                    ON pu.identificador = d.puja
                INNER JOIN itemsCatalogo ic
                    ON ic.identificador = pu.item
                INNER JOIN productos p
                    ON p.identificador = ic.producto
                LEFT JOIN productos_detalle pd
                    ON pd.identificador = p.identificador
                WHERE d.cliente = ?
                  AND d.estado IN ('pendiente', 'vencida')
                ORDER BY d.fechaGeneracion DESC
                """, (rs, rowNum) -> DifferenceResponse.builder()
                        .differenceId(rs.getInt("difference_id"))
                        .itemTitle(rs.getString("item_title"))
                        .lotCode(rs.getString("lot_code"))
                        .winningBid(rs.getBigDecimal("winning_bid"))
                        .availableBalance(rs.getBigDecimal("available_balance"))
                        .differenceAmount(rs.getBigDecimal("difference_amount"))
                        .status(rs.getString("status"))
                        .deadlineHours(72)
                        .accountStatus(resolveAccountStatus(userId))
                        .build(),
                userId);

        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Paga la diferencia de saldo usando el medio de pago indicado.
     * Solo funciona con medios de pago que tengan saldo suficiente (no tarjeta de crédito requiere saldo real).
     */
    @Transactional
    public void payDifference(Integer userId, Integer differenceId, Integer paymentMethodId) {
        syncDifferenceStates();

        if (paymentMethodId == null) {
            throw new IllegalArgumentException("payment_method_id requerido");
        }

        // Verificar que la diferencia pertenece al usuario y está impaga
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM diferencias_saldo
                WHERE identificador = ?
                  AND cliente = ?
                  AND estado IN ('pendiente', 'vencida')
                """, Integer.class, differenceId, userId);

        if (count == null || count == 0) {
            throw new NotFoundException("Diferencia no encontrada.");
        }

        Map<String, Object> difference = jdbcTemplate.queryForMap("""
                SELECT importeDiferencia
                FROM diferencias_saldo
                WHERE identificador = ?
                  AND cliente = ?
                  AND estado IN ('pendiente', 'vencida')
                """, differenceId, userId);

        BigDecimal differenceAmount = (BigDecimal) difference.get("importeDiferencia");

        // Verificar el medio de pago
        List<Map<String, Object>> paymentMethods = jdbcTemplate.queryForList("""
                SELECT montoDisponible, montoUsado, estado, tipo
                FROM mediosDePago
                WHERE identificador = ?
                  AND cliente = ?
                """, paymentMethodId, userId);

        if (paymentMethods.isEmpty()) {
            throw new NotFoundException("Medio de pago no encontrado.");
        }

        Map<String, Object> pm = paymentMethods.get(0);
        String estado = (String) pm.get("estado");
        String tipo = (String) pm.get("tipo");

        if (!"verificado".equalsIgnoreCase(estado)) {
            throw new PaymentRequiredException("El medio de pago no está verificado.");
        }

        // Para medios de pago que no son tarjeta de crédito, se verifica saldo
        if (!"tarjeta_credito".equalsIgnoreCase(tipo)) {
            BigDecimal available = pm.get("montoDisponible") != null
                    ? (BigDecimal) pm.get("montoDisponible")
                    : BigDecimal.ZERO;
            BigDecimal used = pm.get("montoUsado") != null
                    ? (BigDecimal) pm.get("montoUsado")
                    : BigDecimal.ZERO;

            BigDecimal remaining = available.subtract(used);
            if (remaining.compareTo(differenceAmount) < 0) {
                throw new PaymentRequiredException("Saldo insuficiente para abonar la diferencia.");
            }

            jdbcTemplate.update("""
                    UPDATE mediosDePago
                    SET montoUsado = montoUsado + ?
                    WHERE identificador = ?
                      AND cliente = ?
                    """, differenceAmount, paymentMethodId, userId);
        }

        // Marcar diferencia como pagada
        jdbcTemplate.update("""
                UPDATE diferencias_saldo
                SET estado = 'pagada',
                    fechaPago = GETDATE(),
                    medioPago = ?
                WHERE identificador = ?
                  AND cliente = ?
                """, paymentMethodId, differenceId, userId);

        refreshUserAccountStatus(userId);
    }

    /**
     * Job programado: vence diferencias que no fueron pagadas en 72 horas y bloquea usuarios.
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void syncDifferenceStates() {
        // Pasar a 'vencida' si superó el plazo de 72 horas
        jdbcTemplate.update("""
                UPDATE diferencias_saldo
                SET estado = 'vencida'
                WHERE estado = 'pendiente'
                  AND fechaPago IS NULL
                  AND fechaLimitePago <= GETDATE()
                """);

        // Bloquear usuarios con diferencias vencidas
        List<Integer> affectedUsers = jdbcTemplate.query("""
                SELECT DISTINCT cliente
                FROM diferencias_saldo
                WHERE estado IN ('pendiente', 'vencida')
                """, (rs, rowNum) -> rs.getInt("cliente"));

        for (Integer uid : affectedUsers) {
            refreshUserAccountStatus(uid);
        }
    }

    private void refreshUserAccountStatus(Integer userId) {
        // Prioridad 1: multas judiciales (estado más grave)
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

        // Prioridad 2: multas pendientes/vencidas
        Integer fineCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM multas
                WHERE cliente = ?
                  AND estado IN ('pendiente', 'vencida')
                """, Integer.class, userId);

        if (fineCount != null && fineCount > 0) {
            jdbcTemplate.update("""
                    UPDATE usuarios_app
                    SET estadoApp = 'bloqueado',
                        bloqueadoHasta = DATEADD(HOUR, 72, GETDATE())
                    WHERE identificador = ?
                    """, userId);
            return;
        }

        // Prioridad 3: diferencias de saldo pendientes/vencidas
        Integer differenceCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM diferencias_saldo
                WHERE cliente = ?
                  AND estado IN ('pendiente', 'vencida')
                """, Integer.class, userId);

        if (differenceCount != null && differenceCount > 0) {
            jdbcTemplate.update("""
                    UPDATE usuarios_app
                    SET estadoApp = 'bloqueado',
                        bloqueadoHasta = DATEADD(HOUR, 72, GETDATE())
                    WHERE identificador = ?
                    """, userId);
            return;
        }

        // Sin deudas: rehabilitar
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
