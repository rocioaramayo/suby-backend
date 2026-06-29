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

    private static final String SUBMISSION_SUMMARY_WITH_DEPOSIT =
            "Tu artículo necesita ser evaluado. A continuación encontrarás los datos reales del depósito asignado.";
    private static final String SUBMISSION_SUMMARY_PENDING_DEPOSIT =
            "El equipo te informará próximamente el lugar de entrega.";

    private final JdbcTemplate jdbcTemplate;

    public AssignedDeposit resolveAssignedDeposit(Integer requestedDepositId) {
        ensureDepositsTableExists();

        if (requestedDepositId != null && requestedDepositId > 0) {
            AssignedDeposit requestedDeposit = loadDepositById(requestedDepositId);
            if (requestedDeposit == null) {
                throw new OwnerProductValidationException("El depósito seleccionado no existe.");
            }
            return requestedDeposit;
        }

        List<AssignedDeposit> availableDeposits = listAvailableDeposits();
        if (availableDeposits.isEmpty()) {
            throw new OwnerProductValidationException(
                    "No hay depósitos configurados. No pudimos registrar el artículo sin un depósito válido."
            );
        }

        return availableDeposits.get(0);
    }

    public AssignedDeposit loadAssignedDepositForProduct(Integer productId) {
        if (productId == null || productId <= 0 || !tableExists("productos_ext") || !tableExists("depositos")) {
            return null;
        }

        String statusCase = statusPriorityExpression("dep");
        String clarificationSelect = optionalTextColumn("dep", "aclaracion", "clarification");
        String hoursSelect = optionalTextColumn("dep", "horarios", "hours");
        String daysSelect = optionalTextColumn("dep", "dias", "days");

        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1
                        dep.identificador AS deposit_id,
                        %s,
                        %s,
                        %s,
                        %s,
                        %s
                    FROM productos_ext pe
                    JOIN depositos dep ON dep.identificador = pe.deposito
                    WHERE pe.identificador = ?
                    ORDER BY %s, dep.identificador ASC
                    """.formatted(
                    requiredTextColumn("dep", "nombre", "deposit_name", "'Depósito asignado'"),
                    optionalTextColumn("dep", "direccion", "deposit_address"),
                    clarificationSelect,
                    hoursSelect,
                    daysSelect,
                    statusCase
            ), (rs, rowNum) -> mapDeposit(rs), productId);
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

        if (deposit == null) {
            return;
        }

        putIfPresent(data, "location", deposit.name());
        putIfPresent(data, "address", deposit.address());
        putIfPresent(data, "clarification", deposit.clarification());
        putIfPresent(data, "hours", deposit.hours());
        putIfPresent(data, "days", deposit.days());
    }

    private void ensureDepositsTableExists() {
        if (!tableExists("depositos")) {
            throw new OwnerProductValidationException(
                    "La tabla de depósitos no está disponible. No pudimos registrar el artículo."
            );
        }
    }

    private List<AssignedDeposit> listAvailableDeposits() {
        String statusCase = statusPriorityExpression("dep");
        String clarificationSelect = optionalTextColumn("dep", "aclaracion", "clarification");
        String hoursSelect = optionalTextColumn("dep", "horarios", "hours");
        String daysSelect = optionalTextColumn("dep", "dias", "days");

        return jdbcTemplate.query("""
                SELECT
                    dep.identificador AS deposit_id,
                    %s,
                    %s,
                    %s,
                    %s,
                    %s
                FROM depositos dep
                ORDER BY %s, dep.identificador ASC
                """.formatted(
                requiredTextColumn("dep", "nombre", "deposit_name", "CONCAT('Depósito ', dep.identificador)"),
                optionalTextColumn("dep", "direccion", "deposit_address"),
                clarificationSelect,
                hoursSelect,
                daysSelect,
                statusCase
        ), (rs, rowNum) -> mapDeposit(rs));
    }

    private AssignedDeposit loadDepositById(Integer depositId) {
        String clarificationSelect = optionalTextColumn("dep", "aclaracion", "clarification");
        String hoursSelect = optionalTextColumn("dep", "horarios", "hours");
        String daysSelect = optionalTextColumn("dep", "dias", "days");

        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1
                        dep.identificador AS deposit_id,
                        %s,
                        %s,
                        %s,
                        %s,
                        %s
                    FROM depositos dep
                    WHERE dep.identificador = ?
                    """.formatted(
                    requiredTextColumn("dep", "nombre", "deposit_name", "CONCAT('Depósito ', dep.identificador)"),
                    optionalTextColumn("dep", "direccion", "deposit_address"),
                    clarificationSelect,
                    hoursSelect,
                    daysSelect
            ), (rs, rowNum) -> mapDeposit(rs), depositId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private AssignedDeposit mapDeposit(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AssignedDeposit(
                rs.getInt("deposit_id"),
                trimToNull(rs.getString("deposit_name")),
                trimToNull(rs.getString("deposit_address")),
                trimToNull(rs.getString("clarification")),
                trimToNull(rs.getString("hours")),
                trimToNull(rs.getString("days"))
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

    private String optionalTextColumn(String alias, String columnName, String projectionAlias) {
        if (!columnExists("depositos", columnName)) {
            return "CAST(NULL AS VARCHAR(255)) AS " + projectionAlias;
        }

        return "CAST(%s.%s AS VARCHAR(255)) AS %s".formatted(alias, columnName, projectionAlias);
    }

    private String requiredTextColumn(String alias, String columnName, String projectionAlias, String fallbackExpression) {
        if (!columnExists("depositos", columnName)) {
            return "%s AS %s".formatted(fallbackExpression, projectionAlias);
        }

        return "CAST(COALESCE(NULLIF(LTRIM(RTRIM(%s.%s)), ''), %s) AS VARCHAR(255)) AS %s"
                .formatted(alias, columnName, fallbackExpression, projectionAlias);
    }

    private String statusPriorityExpression(String alias) {
        if (!columnExists("depositos", "estado")) {
            return "0";
        }

        return """
                CASE
                    WHEN LOWER(LTRIM(RTRIM(COALESCE(%s.estado, '')))) IN ('activo', 'activa', 'habilitado', 'habilitada', 'si')
                        THEN 0
                    WHEN NULLIF(LTRIM(RTRIM(%s.estado)), '') IS NULL
                        THEN 1
                    ELSE 2
                END
                """.formatted(alias, alias).trim();
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

    private boolean columnExists(String tableName, String columnName) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_NAME = ?
                      AND COLUMN_NAME = ?
                    """, Integer.class, tableName, columnName);
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
            String address,
            String clarification,
            String hours,
            String days
    ) {
    }
}
