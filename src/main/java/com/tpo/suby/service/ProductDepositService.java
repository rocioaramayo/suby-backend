package com.tpo.suby.service;

import java.util.List;
import java.util.Map;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.tpo.suby.exception.OwnerProductValidationException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductDepositService {

    private static final String DEFAULT_SUBMISSION_HOURS = "8:00 A 23:00 Hs";
    private static final String DEFAULT_SUBMISSION_DAYS = "Lunes A Jueves";
    private static final String SUBMISSION_SUMMARY_WITH_DEPOSIT =
            "Tu articulo necesita ser evaluado. A continuacion encontraras los datos reales del deposito asignado.";
    private static final String SUBMISSION_SUMMARY_PENDING_DEPOSIT =
            "El equipo te informara proximamente el lugar de entrega.";

    private final JdbcTemplate jdbcTemplate;

    public AssignedDeposit resolveAssignedDeposit(Integer requestedDepositId) {
        ensureDepositsTableExists();

        if (requestedDepositId != null && requestedDepositId > 0) {
            AssignedDeposit requestedDeposit = loadDepositById(requestedDepositId);
            if (requestedDeposit == null) {
                throw new OwnerProductValidationException("El deposito seleccionado no existe.");
            }
            return requestedDeposit;
        }

        List<AssignedDeposit> availableDeposits = listAvailableDeposits();
        if (availableDeposits.isEmpty()) {
            throw new OwnerProductValidationException(
                    "No hay depositos configurados. No pudimos registrar el articulo sin un deposito valido."
            );
        }

        return availableDeposits.get(0);
    }

    public AssignedDeposit loadAssignedDepositForProduct(Integer productId) {
        if (productId == null || productId <= 0 || !tableExists("productos_ext") || !tableExists("depositos")) {
            return null;
        }

        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1
                        dep.identificador AS deposit_id,
                        CAST(COALESCE(NULLIF(LTRIM(RTRIM(dep.nombre)), ''), CONCAT('Deposito ', dep.identificador)) AS VARCHAR(255)) AS deposit_name,
                        CAST(dep.direccion AS VARCHAR(255)) AS deposit_address
                    FROM productos_ext pe
                    JOIN depositos dep ON dep.identificador = pe.deposito
                    WHERE pe.identificador = ?
                    ORDER BY dep.identificador ASC
                    """, (rs, rowNum) -> mapDeposit(rs), productId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public void applySubmissionDepositData(Map<String, String> data, Integer productId) {
        applySubmissionDepositData(data, loadAssignedDepositForProduct(productId));
    }

    public void applySubmissionDepositData(Map<String, String> data, AssignedDeposit deposit) {
        clearSubmissionDepositData(data);
        data.put("inspection_summary", deposit == null ? SUBMISSION_SUMMARY_PENDING_DEPOSIT : SUBMISSION_SUMMARY_WITH_DEPOSIT);
        data.put("hours", DEFAULT_SUBMISSION_HOURS);
        data.put("days", DEFAULT_SUBMISSION_DAYS);

        if (deposit == null) {
            return;
        }

        putIfPresent(data, "location", deposit.name());
        putIfPresent(data, "address", deposit.address());
    }

    private void ensureDepositsTableExists() {
        if (!tableExists("depositos")) {
            throw new OwnerProductValidationException(
                    "La tabla de depositos no esta disponible. No pudimos registrar el articulo."
            );
        }
    }

    private List<AssignedDeposit> listAvailableDeposits() {
        return jdbcTemplate.query("""
                SELECT
                    dep.identificador AS deposit_id,
                    CAST(COALESCE(NULLIF(LTRIM(RTRIM(dep.nombre)), ''), CONCAT('Deposito ', dep.identificador)) AS VARCHAR(255)) AS deposit_name,
                    CAST(dep.direccion AS VARCHAR(255)) AS deposit_address
                FROM depositos dep
                ORDER BY dep.identificador ASC
                """, (rs, rowNum) -> mapDeposit(rs));
    }

    private AssignedDeposit loadDepositById(Integer depositId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1
                        dep.identificador AS deposit_id,
                        CAST(COALESCE(NULLIF(LTRIM(RTRIM(dep.nombre)), ''), CONCAT('Deposito ', dep.identificador)) AS VARCHAR(255)) AS deposit_name,
                        CAST(dep.direccion AS VARCHAR(255)) AS deposit_address
                    FROM depositos dep
                    WHERE dep.identificador = ?
                    """, (rs, rowNum) -> mapDeposit(rs), depositId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private AssignedDeposit mapDeposit(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AssignedDeposit(
                rs.getInt("deposit_id"),
                trimToNull(rs.getString("deposit_name")),
                trimToNull(rs.getString("deposit_address"))
        );
    }

    private void clearSubmissionDepositData(Map<String, String> data) {
        data.remove("location");
        data.remove("address");
        data.remove("clarification");
        data.remove("hours");
        data.remove("days");
    }

    private void putIfPresent(Map<String, String> data, String key, String value) {
        String trimmed = trimToNull(value);
        if (trimmed != null) {
            data.put(key, trimmed);
        }
    }

    private boolean tableExists(String tableName) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_NAME = ?
                    """, Integer.class, tableName);
            return count != null && count > 0;
        } catch (BadSqlGrammarException ex) {
            return false;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record AssignedDeposit(
            Integer id,
            String name,
            String address
    ) {
    }
}
