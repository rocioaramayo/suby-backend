package com.tpo.suby.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuctionLotStateService {

    private final JdbcTemplate jdbcTemplate;

    public Integer currentActiveItemId(Integer auctionId) {
        ensureTable();

        return jdbcTemplate.query("""
                SELECT TOP 1 ic.identificador
                FROM catalogos c
                JOIN itemsCatalogo ic ON ic.catalogo = c.identificador
                WHERE c.subasta = ?
                  AND COALESCE(ic.subastado, 'no') = 'no'
                ORDER BY ic.identificador ASC
                """, rs -> rs.next() ? rs.getInt(1) : null, auctionId);
    }

    public boolean isActiveLot(Integer auctionId, Integer itemId) {
        Integer current = currentActiveItemId(auctionId);
        return current != null && current.equals(itemId);
    }

    public LotState ensureActiveLotState(Integer auctionId, Integer itemId) {
        ensureTable();

        LotState existing = findState(auctionId, itemId);
        if (existing != null && "en_vivo".equalsIgnoreCase(existing.status())) {
            return existing;
        }

        if (existing == null) {
            jdbcTemplate.update("""
                    INSERT INTO subasta_lote_estado (
                        subasta, item, iniciadoEn, ultimaActividadEn, estado
                    )
                    VALUES (?, ?, GETDATE(), GETDATE(), 'en_vivo')
                    """, auctionId, itemId);
        } else {
            jdbcTemplate.update("""
                    UPDATE subasta_lote_estado
                    SET estado = 'en_vivo',
                        iniciadoEn = COALESCE(iniciadoEn, GETDATE()),
                        ultimaActividadEn = COALESCE(ultimaActividadEn, GETDATE())
                    WHERE identificador = ?
                    """, existing.id());
        }

        return findState(auctionId, itemId);
    }

    public void touchActivity(Integer auctionId, Integer itemId) {
        ensureTable();
        LotState state = ensureActiveLotState(auctionId, itemId);
        jdbcTemplate.update("""
                UPDATE subasta_lote_estado
                SET ultimaActividadEn = GETDATE(),
                    estado = 'en_vivo'
                WHERE identificador = ?
                """, state.id());
    }

    public long secondsRemaining(Integer auctionId, Integer itemId, int inactivitySeconds) {
        if (!isActiveLot(auctionId, itemId)) {
            return 0;
        }

        LotState state = ensureActiveLotState(auctionId, itemId);
        Long remaining = jdbcTemplate.queryForObject("""
                SELECT CASE
                    WHEN DATEDIFF(SECOND, ?, GETDATE()) >= ? THEN 0
                    ELSE ? - DATEDIFF(SECOND, ?, GETDATE())
                END
                """, Long.class, java.sql.Timestamp.valueOf(state.lastActivityAt()), inactivitySeconds, inactivitySeconds,
                java.sql.Timestamp.valueOf(state.lastActivityAt()));

        return remaining == null ? 0 : Math.max(0, remaining);
    }

    public boolean shouldSettle(Integer auctionId, Integer itemId, int inactivitySeconds) {
        if (!isActiveLot(auctionId, itemId)) {
            return false;
        }

        LotState state = ensureActiveLotState(auctionId, itemId);
        Integer elapsed = jdbcTemplate.queryForObject("""
                SELECT DATEDIFF(SECOND, ?, GETDATE())
                """, Integer.class, java.sql.Timestamp.valueOf(state.lastActivityAt()));

        return elapsed != null && elapsed >= inactivitySeconds;
    }

    public void markClosed(Integer auctionId, Integer itemId) {
        ensureTable();
        jdbcTemplate.update("""
                UPDATE subasta_lote_estado
                SET estado = 'cerrado'
                WHERE subasta = ?
                  AND item = ?
                """, auctionId, itemId);
    }

    private LotState findState(Integer auctionId, Integer itemId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT TOP 1 identificador, iniciadoEn, ultimaActividadEn, estado
                    FROM subasta_lote_estado
                    WHERE subasta = ?
                      AND item = ?
                    ORDER BY identificador DESC
                    """, (rs, rowNum) -> new LotState(
                    rs.getInt("identificador"),
                    rs.getTimestamp("iniciadoEn").toLocalDateTime(),
                    rs.getTimestamp("ultimaActividadEn").toLocalDateTime(),
                    rs.getString("estado")
            ), auctionId, itemId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private void ensureTable() {
        jdbcTemplate.execute("""
                IF OBJECT_ID('dbo.subasta_lote_estado', 'U') IS NULL
                BEGIN
                    CREATE TABLE subasta_lote_estado (
                        identificador INT IDENTITY(1,1) PRIMARY KEY,
                        subasta INT NOT NULL,
                        item INT NOT NULL,
                        iniciadoEn DATETIME NOT NULL DEFAULT GETDATE(),
                        ultimaActividadEn DATETIME NOT NULL DEFAULT GETDATE(),
                        estado VARCHAR(20) NOT NULL DEFAULT 'en_vivo'
                    );
                END
                """);
    }

    public record LotState(
            Integer id,
            LocalDateTime startedAt,
            LocalDateTime lastActivityAt,
            String status
    ) {}
}
